/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package kafka.controller

import collection._
import collection.JavaConversions._
import java.util.concurrent.atomic.AtomicBoolean
import kafka.api.LeaderAndIsr
import kafka.common.{TopicAndPartition, StateChangeFailedException, PartitionOfflineException}
import kafka.utils.{Logging, ZkUtils}
import org.I0Itec.zkclient.IZkChildListener
import org.I0Itec.zkclient.exception.ZkNodeExistsException

/**
 * This class represents the state machine for partitions. It defines the states that a partition can be in, and
 * transitions to move the partition to another legal state. The different states that a partition can be in are -
 * 1. NonExistentPartition: This state indicates that the partition was either never created or was created and then
 *                          deleted. Valid previous state, if one exists, is OfflinePartition
 * 2. NewPartition        : After creation, the partition is in the NewPartition state. In this state, the partition should have
 *                          replicas assigned to it, but no leader/isr yet. Valid previous states are NonExistentPartition
 * 3. OnlinePartition     : Once a leader is elected for a partition, it is in the OnlinePartition state.
 *                          Valid previous states are NewPartition/OfflinePartition
 * 4. OfflinePartition    : If, after successful leader election, the leader for partition dies, then the partition
 *                          moves to the OfflinePartition state. Valid previous states are NewPartition/OnlinePartition
 */
class PartitionStateMachine(controller: KafkaController) extends Logging {
  this.logIdent = "[Partition state machine on Controller " + controller.config.brokerId + "]: "
  private val controllerContext = controller.controllerContext
  private val zkClient = controllerContext.zkClient
  var partitionState: mutable.Map[TopicAndPartition, PartitionState] = mutable.Map.empty
  val brokerRequestBatch = new ControllerBrokerRequestBatch(controller.sendRequest)
  val offlinePartitionSelector = new OfflinePartitionLeaderSelector(controllerContext)
  private val isShuttingDown = new AtomicBoolean(false)

  /**
   * Invoked on successful controller election. First registers a topic change listener since that triggers all
   * state transitions for partitions. Initializes the state of partitions by reading from zookeeper. Then triggers
   * the OnlinePartition state change for all new or offline partitions.
   */
  def startup() {
    isShuttingDown.set(false)
    // initialize partition state
    initializePartitionState()
    // try to move partitions to online state
    triggerOnlinePartitionStateChange()
    info("Started partition state machine with initial state -> " + partitionState.toString())
  }

  // register topic and partition change listeners
  def registerListeners() {
    registerTopicChangeListener()
  }

  /**
   * Invoked on controller shutdown.
   */
  def shutdown() {
    isShuttingDown.compareAndSet(false, true)
    partitionState.clear()
  }

  /**
   * This API invokes the OnlinePartition state change on all partitions in either the NewPartition or OfflinePartition
   * state. This is called on a successful controller election and on broker changes
   */
  def triggerOnlinePartitionStateChange() {
    try {
      brokerRequestBatch.newBatch()
      // try to move all partitions in NewPartition or OfflinePartition state to OnlinePartition state
      for((topicAndPartition, partitionState) <- partitionState) {
        if(partitionState.equals(OfflinePartition) || partitionState.equals(NewPartition))
          handleStateChange(topicAndPartition.topic, topicAndPartition.partition, OnlinePartition, offlinePartitionSelector)
      }
      brokerRequestBatch.sendRequestsToBrokers(controller.epoch, controllerContext.liveBrokers)
    } catch {
      case e => error("Error while moving some partitions to the online state", e)
    }
  }

  /**
   * This API is invoked by the partition change zookeeper listener
   * @param partitions   The list of partitions that need to be transitioned to the target state
   * @param targetState  The state that the partitions should be moved to
   */
  def handleStateChanges(partitions: Set[TopicAndPartition], targetState: PartitionState,
                         leaderSelector: PartitionLeaderSelector = offlinePartitionSelector) {
    info("Invoking state change to %s for partitions %s".format(targetState, partitions.mkString(",")))
    try {
      brokerRequestBatch.newBatch()
      partitions.foreach { topicAndPartition =>
        handleStateChange(topicAndPartition.topic, topicAndPartition.partition, targetState, leaderSelector)
      }
      brokerRequestBatch.sendRequestsToBrokers(controller.epoch, controllerContext.liveBrokers)
    }catch {
      case e => error("Error while moving some partitions to %s state".format(targetState), e)
    }
  }

  /**
   * This API exercises the partition's state machine. It ensures that every state transition happens from a legal
   * previous state to the target state.
   * @param topic       The topic of the partition for which the state transition is invoked
   * @param partition   The partition for which the state transition is invoked
   * @param targetState The end state that the partition should be moved to
   */
  private def handleStateChange(topic: String, partition: Int, targetState: PartitionState,
                                leaderSelector: PartitionLeaderSelector) {
    val topicAndPartition = TopicAndPartition(topic, partition)
    val currState = partitionState.getOrElseUpdate(topicAndPartition, NonExistentPartition)
    try {
      targetState match {
        case NewPartition =>
          // pre: partition did not exist before this
          // post: partition has been assigned replicas
          assertValidPreviousStates(topicAndPartition, List(NonExistentPartition), NewPartition)
          assignReplicasToPartitions(topic, partition)
          partitionState.put(topicAndPartition, NewPartition)
          info("Partition [%s, %d] state changed from NotExists to New with assigned replicas ".format(topic, partition) +
            "%s".format(controllerContext.partitionReplicaAssignment(topicAndPartition).mkString(",")))
        case OnlinePartition =>
          assertValidPreviousStates(topicAndPartition, List(NewPartition, OnlinePartition, OfflinePartition), OnlinePartition)
          partitionState(topicAndPartition) match {
            case NewPartition =>
              // initialize leader and isr path for new partition
              initializeLeaderAndIsrForPartition(topicAndPartition)
            case OfflinePartition =>
              electLeaderForPartition(topic, partition, leaderSelector)
            case OnlinePartition => // invoked when the leader needs to be re-elected
              electLeaderForPartition(topic, partition, leaderSelector)
            case _ => // should never come here since illegal previous states are checked above
          }
          info("Partition [%s, %d] state changed from %s to OnlinePartition with leader %d".format(topic, partition,
            partitionState(topicAndPartition), controllerContext.allLeaders(topicAndPartition).leaderAndIsr.leader))
          partitionState.put(topicAndPartition, OnlinePartition)
           // post: partition has a leader
        case OfflinePartition =>
          // pre: partition should be in Online state
          assertValidPreviousStates(topicAndPartition, List(NewPartition, OnlinePartition), OfflinePartition)
          // should be called when the leader for a partition is no longer alive
          info("Partition [%s, %d] state changed from Online to Offline".format(topic, partition))
          partitionState.put(topicAndPartition, OfflinePartition)
          // post: partition has no alive leader
        case NonExistentPartition =>
          // pre: partition could be in either of the above states
          assertValidPreviousStates(topicAndPartition, List(OfflinePartition), NonExistentPartition)
          info("Partition [%s, %d] state changed from Offline to NotExists".format(topic, partition))
          partitionState.put(topicAndPartition, NonExistentPartition)
          // post: partition state is deleted from all brokers and zookeeper
      }
    } catch {
      case t: Throwable => error("State change for partition [%s, %d] ".format(topic, partition) +
        "from %s to %s failed".format(currState, targetState), t)
    }
  }

  /**
   * Invoked on startup of the partition's state machine to set the initial state for all existing partitions in
   * zookeeper
   */
  private def initializePartitionState() {
    for((topicPartition, replicaAssignment) <- controllerContext.partitionReplicaAssignment) {
      val topic = topicPartition.topic
      val partition = topicPartition.partition
      // check if leader and isr path exists for partition. If not, then it is in NEW state
      ZkUtils.getLeaderAndIsrForPartition(zkClient, topic, partition) match {
        case Some(currentLeaderAndIsr) =>
          // else, check if the leader for partition is alive. If yes, it is in Online state, else it is in Offline state
          controllerContext.liveBrokerIds.contains(currentLeaderAndIsr.leader) match {
            case true => // leader is alive
              partitionState.put(topicPartition, OnlinePartition)
            case false =>
              partitionState.put(topicPartition, OfflinePartition)
          }
        case None =>
          partitionState.put(topicPartition, NewPartition)
      }
    }
  }

  private def assertValidPreviousStates(topicAndPartition: TopicAndPartition, fromStates: Seq[PartitionState],
                                        targetState: PartitionState) {
    if(!fromStates.contains(partitionState(topicAndPartition)))
      throw new IllegalStateException("Partition %s should be in the %s states before moving to %s state"
        .format(topicAndPartition, fromStates.mkString(","), targetState) + ". Instead it is in %s state"
        .format(partitionState(topicAndPartition)))
  }

  /**
   * Invoked on the NonExistentPartition->NewPartition state transition to update the controller's cache with the
   * partition's replica assignment.
   * @param topic     The topic of the partition whose replica assignment is to be cached
   * @param partition The partition whose replica assignment is to be cached
   */
  private def assignReplicasToPartitions(topic: String, partition: Int) {
    val assignedReplicas = ZkUtils.getReplicasForPartition(controllerContext.zkClient, topic, partition)
    controllerContext.partitionReplicaAssignment += TopicAndPartition(topic, partition) -> assignedReplicas
  }

  /**
   * Invoked on the NewPartition->OnlinePartition state change. When a partition is in the New state, it does not have
   * a leader and isr path in zookeeper. Once the partition moves to the OnlinePartition state, it's leader and isr
   * path gets initialized and it never goes back to the NewPartition state. From here, it can only go to the
   * OfflinePartition state.
   * @param topicAndPartition   The topic/partition whose leader and isr path is to be initialized
   */
  private def initializeLeaderAndIsrForPartition(topicAndPartition: TopicAndPartition) {
    debug("Initializing leader and isr for partition %s".format(topicAndPartition))
    val replicaAssignment = controllerContext.partitionReplicaAssignment(topicAndPartition)
    val liveAssignedReplicas = replicaAssignment.filter(r => controllerContext.liveBrokerIds.contains(r))
    liveAssignedReplicas.size match {
      case 0 =>
        ControllerStat.offlinePartitionRate.mark()
        throw new StateChangeFailedException(("During state change of partition %s from NEW to ONLINE, assigned replicas are " +
          "[%s], live brokers are [%s]. No assigned replica is alive").format(topicAndPartition,
          replicaAssignment.mkString(","), controllerContext.liveBrokerIds))
      case _ =>
        debug("Live assigned replicas for partition %s are: [%s]".format(topicAndPartition, liveAssignedReplicas))
        // make the first replica in the list of assigned replicas, the leader
        val leader = liveAssignedReplicas.head
        val leaderIsrAndControllerEpoch = new LeaderIsrAndControllerEpoch(new LeaderAndIsr(leader, liveAssignedReplicas.toList),
          controller.epoch)
        try {
          ZkUtils.createPersistentPath(controllerContext.zkClient,
            ZkUtils.getTopicPartitionLeaderAndIsrPath(topicAndPartition.topic, topicAndPartition.partition),
            ZkUtils.leaderAndIsrZkData(leaderIsrAndControllerEpoch.leaderAndIsr, controller.epoch))
          // NOTE: the above write can fail only if the current controller lost its zk session and the new controller
          // took over and initialized this partition. This can happen if the current controller went into a long
          // GC pause
          brokerRequestBatch.addLeaderAndIsrRequestForBrokers(liveAssignedReplicas, topicAndPartition.topic,
            topicAndPartition.partition, leaderIsrAndControllerEpoch, replicaAssignment.size)
          controllerContext.allLeaders.put(topicAndPartition, leaderIsrAndControllerEpoch)
          partitionState.put(topicAndPartition, OnlinePartition)
        } catch {
          case e: ZkNodeExistsException =>
            // read the controller epoch
            val leaderIsrAndEpoch = ZkUtils.getLeaderIsrAndEpochForPartition(zkClient, topicAndPartition.topic,
              topicAndPartition.partition).get
            ControllerStat.offlinePartitionRate.mark()
            throw new StateChangeFailedException("Error while changing partition %s's state from New to Online"
              .format(topicAndPartition) + " since Leader and isr path already exists with value " +
              "%s and controller epoch %d".format(leaderIsrAndEpoch.leaderAndIsr.toString(), leaderIsrAndEpoch.controllerEpoch))
        }
    }
  }

  /**
   * Invoked on the OfflinePartition->OnlinePartition state change. It invokes the leader election API to elect a leader
   * for the input offline partition
   * @param topic               The topic of the offline partition
   * @param partition           The offline partition
   * @param leaderSelector      Specific leader selector (e.g., offline/reassigned/etc.)
   */
  def electLeaderForPartition(topic: String, partition: Int, leaderSelector: PartitionLeaderSelector) {
    // handle leader election for the partitions whose leader is no longer alive
    info("Electing leader for partition [%s, %d]".format(topic, partition))
    try {
      var zookeeperPathUpdateSucceeded: Boolean = false
      var newLeaderAndIsr: LeaderAndIsr = null
      var replicasForThisPartition: Seq[Int] = Seq.empty[Int]
      while(!zookeeperPathUpdateSucceeded) {
        val currentLeaderIsrAndEpoch = getLeaderIsrAndEpochOrThrowException(topic, partition)
        val currentLeaderAndIsr = currentLeaderIsrAndEpoch.leaderAndIsr
        val controllerEpoch = currentLeaderIsrAndEpoch.controllerEpoch
        if(controllerEpoch > controller.epoch)
          throw new StateChangeFailedException("Leader and isr path written by another controller. This probably" +
            "means the current controller with epoch %d went through a soft failure and another ".format(controller.epoch) +
            "controller was elected with epoch %d. Aborting state change by this controller".format(controllerEpoch))
        // elect new leader or throw exception
        val (leaderAndIsr, replicas) = leaderSelector.selectLeader(topic, partition, currentLeaderAndIsr)
        val (updateSucceeded, newVersion) = ZkUtils.conditionalUpdatePersistentPath(zkClient,
          ZkUtils.getTopicPartitionLeaderAndIsrPath(topic, partition),
          ZkUtils.leaderAndIsrZkData(leaderAndIsr, controller.epoch), currentLeaderAndIsr.zkVersion)
        newLeaderAndIsr = leaderAndIsr
        newLeaderAndIsr.zkVersion = newVersion
        zookeeperPathUpdateSucceeded = updateSucceeded
        replicasForThisPartition = replicas
      }
      val newLeaderIsrAndControllerEpoch = new LeaderIsrAndControllerEpoch(newLeaderAndIsr, controller.epoch)
      // update the leader cache
      controllerContext.allLeaders.put(TopicAndPartition(topic, partition), newLeaderIsrAndControllerEpoch)
      info("Elected leader %d for Offline partition [%s, %d]".format(newLeaderAndIsr.leader, topic, partition))
      // store new leader and isr info in cache
      brokerRequestBatch.addLeaderAndIsrRequestForBrokers(replicasForThisPartition, topic, partition,
        newLeaderIsrAndControllerEpoch, controllerContext.partitionReplicaAssignment(TopicAndPartition(topic, partition)).size)
    } catch {
      case poe: PartitionOfflineException => throw new PartitionOfflineException("All replicas for partition [%s, %d] are dead."
        .format(topic, partition) + " Marking this partition offline", poe)
      case sce => throw new StateChangeFailedException(("Error while electing leader for partition " +
        " [%s, %d] due to: %s.").format(topic, partition, sce.getMessage), sce)
    }
    debug("After leader election, leader cache is updated to %s".format(controllerContext.allLeaders.map(l => (l._1, l._2))))
  }

  private def registerTopicChangeListener() = {
    zkClient.subscribeChildChanges(ZkUtils.BrokerTopicsPath, new TopicChangeListener())
  }

  def registerPartitionChangeListener(topic: String) = {
    zkClient.subscribeChildChanges(ZkUtils.getTopicPath(topic), new PartitionChangeListener(topic))
  }

  private def getLeaderIsrAndEpochOrThrowException(topic: String, partition: Int): LeaderIsrAndControllerEpoch = {
    ZkUtils.getLeaderIsrAndEpochForPartition(zkClient, topic, partition) match {
      case Some(currentLeaderIsrAndEpoch) => currentLeaderIsrAndEpoch
      case None =>
        throw new StateChangeFailedException("Leader and ISR information doesn't exist for partition " +
          "[%s, %d] in %s state".format(topic, partition, partitionState(TopicAndPartition(topic, partition))))
    }
  }

  /**
   * This is the zookeeper listener that triggers all the state transitions for a partition
   */
  class TopicChangeListener extends IZkChildListener with Logging {
    this.logIdent = "[TopicChangeListener on Controller " + controller.config.brokerId + "]: "

    @throws(classOf[Exception])
    def handleChildChange(parentPath : String, children : java.util.List[String]) {
      if(!isShuttingDown.get()) {
        controllerContext.controllerLock synchronized {
          try {
            debug("Topic change listener fired for path %s with children %s".format(parentPath, children.mkString(",")))
            val currentChildren = JavaConversions.asBuffer(children).toSet
            val newTopics = currentChildren -- controllerContext.allTopics
            val deletedTopics = controllerContext.allTopics -- currentChildren
            //        val deletedPartitionReplicaAssignment = replicaAssignment.filter(p => deletedTopics.contains(p._1._1))
            controllerContext.allTopics = currentChildren

            val addedPartitionReplicaAssignment = ZkUtils.getReplicaAssignmentForTopics(zkClient, newTopics.toSeq)
            controllerContext.partitionReplicaAssignment = controllerContext.partitionReplicaAssignment.filter(p =>
              !deletedTopics.contains(p._1.topic))
            controllerContext.partitionReplicaAssignment.++=(addedPartitionReplicaAssignment)
            info("New topics: [%s], deleted topics: [%s], new partition replica assignment [%s]".format(newTopics,
              deletedTopics, addedPartitionReplicaAssignment))
            if(newTopics.size > 0)
              controller.onNewTopicCreation(newTopics, addedPartitionReplicaAssignment.keySet.toSet)
          } catch {
            case e => error("Error while handling new topic", e )
          }
          // TODO: kafka-330  Handle deleted topics
        }
      }
    }
  }

  class PartitionChangeListener(topic: String) extends IZkChildListener with Logging {
    this.logIdent = "[Controller " + controller.config.brokerId + "], "

    @throws(classOf[Exception])
    def handleChildChange(parentPath : String, children : java.util.List[String]) {
      controllerContext.controllerLock synchronized {
        // TODO: To be completed as part of KAFKA-41
      }
    }
  }
}

sealed trait PartitionState { def state: Byte }
case object NewPartition extends PartitionState { val state: Byte = 0 }
case object OnlinePartition extends PartitionState { val state: Byte = 1 }
case object OfflinePartition extends PartitionState { val state: Byte = 2 }
case object NonExistentPartition extends PartitionState { val state: Byte = 3 }



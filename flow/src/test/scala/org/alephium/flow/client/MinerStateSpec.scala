package org.alephium.flow.client

import akka.testkit.TestProbe
import org.scalacheck.Gen

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.handler.{AllHandlers, BlockChainHandler, TestUtils}
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.setting.MiningSetting
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.{ActorRefT, AVector, Random}

class MinerStateSpec extends AlephiumFlowActorSpec("FairMinerState") { Spec =>
  trait Fixture extends MinerState {
    override implicit def brokerConfig: BrokerConfig  = config.broker
    override implicit def miningConfig: MiningSetting = config.mining

    val handlers: AllHandlers = TestUtils.createBlockHandlersProbe._1
    val probes                = AVector.fill(brokerConfig.groupNumPerBroker, brokerConfig.groups)(TestProbe())

    override def prepareTemplate(fromShift: Int, to: Int): BlockTemplate = {
      val index        = ChainIndex.unsafe(brokerConfig.groupFrom + fromShift, to)
      val flowTemplate = blockFlow.prepareBlockFlowUnsafe(index)
      BlockTemplate(flowTemplate.deps, flowTemplate.target, AVector.empty)
    }

    override def startTask(fromShift: Int,
                           to: Int,
                           template: BlockTemplate,
                           blockHandler: ActorRefT[BlockChainHandler.Command]): Unit = {
      probes(fromShift)(to).ref ! template
    }
  }

  it should "use correct collections" in new Fixture {
    miningCounts.length is brokerConfig.groupNumPerBroker
    miningCounts.foreach(_.length is brokerConfig.groups)
    running.length is brokerConfig.groupNumPerBroker
    running.foreach(_.length is brokerConfig.groups)
    pendingTasks.length is brokerConfig.groupNumPerBroker
    pendingTasks.foreach(_.length is brokerConfig.groups)
  }

  it should "start new tasks correctly" in new Fixture {
    startNewTasks()
    probes.foreach(_.foreach(_.expectMsgType[BlockTemplate]))
    running.foreach(_.foreach(_ is true))
  }

  it should "handle mining counts correctly" in new Fixture {
    forAll(Gen.choose(0, brokerConfig.groupNumPerBroker - 1),
           Gen.choose(0, brokerConfig.groups - 1)) { (fromShift, to) =>
      val oldCount   = getMiningCount(fromShift, to)
      val countDelta = Random.source.nextInt(Integer.MAX_VALUE)
      increaseCounts(fromShift, to, countDelta)
      val newCount = getMiningCount(fromShift, to)
      (newCount - oldCount) is countDelta
    }
  }

  it should "pick up correct task" in new Fixture {
    val fromShift = Random.source.nextInt(brokerConfig.groupNumPerBroker)
    val to        = Random.source.nextInt(brokerConfig.groups)
    (0 until brokerConfig.groups).foreach { i =>
      if (i != to) increaseCounts(fromShift, i, miningConfig.nonceStep + 1)
    }
    startNewTasks()
    (0 until brokerConfig.groups).foreach { i =>
      if (i != to) probes(fromShift)(i).expectNoMessage()
      else probes(fromShift)(i).expectMsgType[BlockTemplate]
    }
  }
}

package org.alephium.flow.client

import akka.actor.{ActorRef, Props}
import akka.util.ByteString
import org.alephium.crypto.ED25519PublicKey
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.model.DataOrigin.LocalMining
import org.alephium.flow.storage.{BlockChainHandler, FlowHandler}
import org.alephium.protocol.model.{Block, ChainIndex, Transaction}
import org.alephium.util.{AVector, BaseActor}

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.Random

object Miner {
  sealed trait Command
  case object Start                          extends Command
  case object Stop                           extends Command
  case class BlockAdded(index: ChainIndex)   extends Command
  case class Nonce(from: BigInt, to: BigInt) extends Command

  def mineGenesis(chainIndex: ChainIndex)(implicit config: PlatformConfig): Block = {
    @tailrec
    def iter(nonce: BigInt): Block = {
      val block = Block.genesis(AVector.empty, config.maxMiningTarget, nonce)
      if (chainIndex.validateDiff(block)) block else iter(nonce + 1)
    }

    iter(0)
  }

  trait Builder {
    def createMiner(address: ED25519PublicKey, node: Node, chainIndex: ChainIndex)(
        implicit config: PlatformConfig): Props =
      Props(new Miner(address, node, chainIndex))
  }
}

class Miner(address: ED25519PublicKey, node: Node, chainIndex: ChainIndex)(
    implicit config: PlatformConfig)
    extends BaseActor {
  import node.allHandlers
  var totalMiningCount    = 0 // This counts how many mining tasks got run so far
  var taskStartingTime    = 0l // This is the starting time for current task
  val taskRefreshDuration = 5.seconds.toMillis

  val blockHandler: ActorRef = allHandlers.getBlockHandler(chainIndex)

  override def receive: Receive = awaitStart

  def awaitStart: Receive = {
    case Miner.Start =>
      log.info("Start mining")
      allHandlers.flowHandler ! FlowHandler.PrepareBlockFlow(chainIndex)
      context become collect
  }

  def awaitStop: Receive = {
    case Miner.Stop =>
      context become awaitStart
  }

  protected def _mine(template: BlockTemplate, lastTs: Long): Receive = {
    case Miner.Nonce(from, to) =>
      totalMiningCount += 1
      tryMine(template, from, to) match {
        case Some(block) =>
          val elapsed = System.currentTimeMillis() - lastTs
          log.info(
            s"A new block ${block.shortHex} got mined for $chainIndex, elapsed $elapsed ms, miningCount: $totalMiningCount, target: ${template.target}")
          blockHandler ! BlockChainHandler.AddBlocks(AVector(block), LocalMining)
        case None =>
          if (System.currentTimeMillis() - taskStartingTime >= taskRefreshDuration) {
            allHandlers.flowHandler ! FlowHandler.PrepareBlockFlow(chainIndex)
            context become collect
          } else {
            self ! Miner.Nonce(to, to + config.nonceStep)
          }
      }

    case _: Miner.BlockAdded =>
      allHandlers.flowHandler ! FlowHandler.PrepareBlockFlow(chainIndex)
      context become collect
  }

  def mine(template: BlockTemplate, lastTs: Long): Receive =
    _mine(template, lastTs) orElse awaitStop

  protected def _collect: Receive = {
    case FlowHandler.BlockFlowTemplate(_, deps, target) =>
      assert(deps.length == (2 * config.groups - 1))
      // scalastyle:off magic.number
      val data         = ByteString.fromInts(Random.nextInt())
      val transactions = AVector.tabulate(1000)(Transaction.coinbase(address, _, data))
      val chainDep     = deps.takeRight(config.groups)(chainIndex.to.value)
      // scalastyle:on magic.number
      node.blockFlow.getBlockHeader(chainDep) match {
        case Left(e) =>
          log.warning(e.toString)
          allHandlers.flowHandler ! FlowHandler.PrepareBlockFlow(chainIndex)
        case Right(header) =>
          val lastTs   = header.timestamp
          val template = BlockTemplate(deps, target, transactions)
          context become mine(template, lastTs)
          taskStartingTime = System.currentTimeMillis()
          self ! Miner.Nonce(0, config.nonceStep)
      }
  }

  def collect: Receive = _collect orElse awaitStop

  def tryMine(template: BlockTemplate, from: BigInt, to: BigInt): Option[Block] = {
    @tailrec
    def iter(current: BigInt): Option[Block] = {
      if (current < to) {
        val header = template.buildHeader(current)
        if (chainIndex.validateDiff(header)) Some(Block(header, template.transactions))
        else iter(current + 1)
      } else None
    }

    iter(from)
  }
}

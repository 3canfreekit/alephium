package org.alephium.appserver

import scala.concurrent.{ExecutionContext, Future}

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.client.FairMiner
import org.alephium.flow.platform.Mode
import org.alephium.util.ActorRefT

class Server(val mode: Mode) extends StrictLogging {

  implicit val executionContext: ExecutionContext = mode.node.system.dispatcher

  private val miner: ActorRefT[FairMiner.Command] = {
    val props =
      FairMiner.props(mode.node)(mode.config).withDispatcher("akka.actor.mining-dispatcher")
    ActorRefT.build(mode.node.system, props, s"FairMiner")
  }

  val rpcServer: RPCServer = RPCServer(mode, miner)

  def start(): Future[Unit] = rpcServer.runServer()

  def stop(): Future[Unit] =
    for {
      _ <- rpcServer.stopServer()
      _ <- mode.shutdown()
    } yield ()
}

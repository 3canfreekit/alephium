// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.appserver

import java.nio.file.Path

import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success}

import akka.actor.ActorSystem
import com.typesafe.config.{Config, ConfigRenderOptions}
import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.FlowMonitor
import org.alephium.flow.setting.{AlephiumConfig, Configs, Platform}
import org.alephium.protocol.model.{Block, NetworkType}
import org.alephium.util.{ActorRefT, AVector}

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
object Boot extends App with StrictLogging {
  val rootPath: Path                              = Platform.getRootPath()
  val networkType: Option[NetworkType]            = Configs.parseNetworkType(rootPath)
  val typesafeConfig: Config                      = Configs.parseConfigAndValidate(rootPath, networkType)
  implicit val config: AlephiumConfig             = AlephiumConfig.loadOrThrow(typesafeConfig)
  implicit val apiConfig: ApiConfig               = ApiConfig.loadOrThrow(typesafeConfig)
  implicit val system: ActorSystem                = ActorSystem("Root", typesafeConfig)
  implicit val executionContext: ExecutionContext = system.dispatcher

  logConfig()

  val flowMonitor: ActorRefT[FlowMonitor.Command] =
    ActorRefT.build(system, FlowMonitor.props(stop()), "FlowMonitor")

  val server: Server = new ServerImpl(rootPath)

  server
    .start()
    .onComplete {
      case Success(_) => ()
      case Failure(e) =>
        logger.error("Fatal error during initialization.", e)
        stop()
    }

  Runtime.getRuntime.addShutdownHook(new Thread(() => {
    stop()
  }))

  def stop(): Unit =
    Await.result(for {
      _ <- server.stop()
      _ <- system.terminate()
    } yield (), FlowMonitor.shutdownTimeout.asScala)

  def logConfig(): Unit = {
    val renderOptions =
      ConfigRenderOptions.defaults().setOriginComments(false).setComments(false).setJson(false)
    logger.debug(typesafeConfig.root().render(renderOptions))

    val digests = config.genesisBlocks.map(showBlocks).mkString("-")
    logger.info(s"Genesis digests: $digests")
  }

  def showBlocks(blocks: AVector[Block]): String = blocks.map(_.shortHex).mkString("-")
}

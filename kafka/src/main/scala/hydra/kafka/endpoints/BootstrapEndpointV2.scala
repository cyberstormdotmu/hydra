/*
 * Copyright (C) 2016 Pluralsight, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package hydra.kafka.endpoints

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import hydra.core.bootstrap.CreateTopicProgram
import hydra.core.http.CorsSupport
import hydra.kafka.model.TopicMetadataV2Request
import hydra.kafka.serializers.TopicMetadataV2Parser

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

final class BootstrapEndpointV2(createTopicProgram: CreateTopicProgram[IO])
                                     (implicit val system: ActorSystem, implicit val e: ExecutionContext) extends CorsSupport {

  import TopicMetadataV2Parser._

  val route: Route = cors(settings) {
    path("v2" / "streams") {
      post {
        pathEndOrSingleSlash {
          entity(as[TopicMetadataV2Request]) { t =>
            onComplete(createTopicProgram.createTopic(t.subject.value, t.schemas.key, t.schemas.value).unsafeToFuture()) {
              case Success(_) => complete(StatusCodes.OK)
              case Failure(e) => complete(StatusCodes.InternalServerError, e)
            }
          }
        }
      }
    }
  }

}

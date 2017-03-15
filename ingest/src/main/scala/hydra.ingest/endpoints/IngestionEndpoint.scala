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

package hydra.ingest.endpoints

import akka.actor._
import akka.http.scaladsl.model.StatusCodes.ServiceUnavailable
import akka.http.scaladsl.server.{ExceptionHandler, RequestContext, Route}
import com.github.vonnagy.service.container.http.routing.RoutedEndpoints
import hydra.common.logging.LoggingAdapter
import hydra.core.http.HydraDirectives
import hydra.core.ingest.IngestionParams
import hydra.core.marshallers.{GenericServiceResponse, HydraJsonSupport}
import hydra.ingest.HydraIngestorRegistry
import hydra.ingest.services.IngestionRequestHandler

/**
  * Created by alexsilva on 12/22/15.
  */
class IngestionEndpoint(implicit val system: ActorSystem, implicit val actorRefFactory: ActorRefFactory)
  extends RoutedEndpoints with LoggingAdapter with HydraJsonSupport with HydraDirectives with HydraIngestorRegistry {

  import hydra.ingest.RequestFactories._

  override val route: Route =
    post {
      requestEntityPresent {
        pathPrefix("ingest") {
          headerValueByName(IngestionParams.HYDRA_REQUEST_LABEL_PARAM) { destination =>
            handleExceptions(excptHandler) {
              pathEndOrSingleSlash {
                broadcastRequest(destination)
              }
            } ~ path(Segment) { ingestor =>
              publishToIngestor(destination, ingestor)
            }
          }
        }
      }
    }

  val excptHandler = ExceptionHandler {
    case e: IllegalArgumentException => complete(GenericServiceResponse(400, e.getMessage))
    case e: Exception => complete(GenericServiceResponse(ServiceUnavailable.intValue, e.getMessage))
  }

  def broadcastRequest(destination: String) = {
    onSuccess(ingestorRegistry) { registry =>
      entity(as[String]) { payload =>
        imperativelyComplete { ictx =>
          val hydraReq = createRequest[String, RequestContext](destination, payload, ictx.ctx)
          actorRefFactory.actorOf(IngestionRequestHandler.props(hydraReq, registry, ictx))
        }
      }
    }
  }

  def publishToIngestor(destination: String, ingestor: String) = {
    onSuccess(lookupIngestor(ingestor)) { result =>
      result.ref match {
        case Some(ref) =>
          entity(as[String]) { payload =>
            imperativelyComplete { ictx =>
              val hydraReq = createRequest[String, RequestContext](destination, payload, ictx.ctx)
              ingestorRegistry.foreach(r => actorRefFactory.actorOf(IngestionRequestHandler.props(hydraReq, r, ictx)))
            }
          }
        case None => complete(404, GenericServiceResponse(404, s"Ingestor $ingestor not found."))
      }
    }
  }
}
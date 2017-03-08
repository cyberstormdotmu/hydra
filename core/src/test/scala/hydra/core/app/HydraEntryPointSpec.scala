package hydra.core.app

import akka.actor.{ActorRefFactory, ActorSystem, Props}
import akka.http.scaladsl.server.Route
import com.github.vonnagy.service.container.http.routing.RoutedEndpoints
import com.github.vonnagy.service.container.service.ContainerService
import com.typesafe.config.{Config, ConfigFactory}
import hydra.core.testing.DummyActor
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FunSpecLike, Matchers}

/**
  * Created by alexsilva on 3/7/17.
  */
class HydraEntryPointSpec extends Matchers with FunSpecLike with MockFactory {
  val conf =
    """
      |  test {
      |    endpoints = ["hydra.core.app.DummyEndpoint"]
      |  }
      |  extensions {
      |    dummy {
      |      enabled = true
      |    }
      |  }
    """.stripMargin

  val et = new HydraEntryPoint() {
    override def moduleName: String = "test"

    override def config: Config = ConfigFactory.parseString(conf)

    override def services: Seq[(String, Props)] = Seq("test" -> Props[DummyActor])
  }

  describe("When using the HydraEntryPoint class") {

    it("is properly configured") {
      et.moduleName shouldBe "test"
      et.services shouldBe Seq("test" -> Props[DummyActor])
      et.endpoints shouldBe Seq(classOf[DummyEndpoint])
      et.extensions shouldBe ConfigFactory.parseString(conf).getConfig("hydraTest.extensions")
    }

    it("builds a container") {
      implicit val system = mock[ActorSystem]
      val csvc = new ContainerService(Seq(classOf[DummyEndpoint]), Nil, Seq("test" -> Props[DummyActor]), Nil,
        "service-container")
      val container = et.buildContainer()
      csvc.name shouldBe container.name
      csvc.registeredRoutes shouldBe container.registeredRoutes
      csvc.name shouldBe container.name
    }
  }
}

private class DummyEndpoint(implicit s: ActorSystem, implicit val a: ActorRefFactory) extends RoutedEndpoints {
  override def route: Route = get {
    complete("DONE")
  }
}
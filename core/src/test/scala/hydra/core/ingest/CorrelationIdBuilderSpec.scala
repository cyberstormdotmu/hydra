package hydra.core.ingest

import hydra.common.util.Base62
import org.scalatest.{FlatSpecLike, Matchers}

import scala.util.Random

class CorrelationIdBuilderSpec extends Matchers with FlatSpecLike {
  "The CorrelationIdBuilder" should "generate the right ids" in {
    CorrelationIdBuilder.generate(123456789L) should be("8M0kX")
    val r = Math.abs(Random.nextLong)
    CorrelationIdBuilder.generate(r) shouldBe new Base62().encode(r)
  }
}
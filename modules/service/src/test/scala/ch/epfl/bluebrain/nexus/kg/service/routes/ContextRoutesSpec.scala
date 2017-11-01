package ch.epfl.bluebrain.nexus.kg.service.routes

import java.time.Clock
import java.util.regex.Pattern.quote

import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.ActorMaterializer
import cats.instances.future._
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.commons.iam.IamClient
import ch.epfl.bluebrain.nexus.commons.iam.identity.Caller.AnonymousCaller
import ch.epfl.bluebrain.nexus.commons.test.{Randomness, Resources}
import ch.epfl.bluebrain.nexus.commons.types.HttpRejection.IllegalVersionFormat
import ch.epfl.bluebrain.nexus.kg.core.CallerCtx
import ch.epfl.bluebrain.nexus.kg.core.contexts.ContextRejection._
import ch.epfl.bluebrain.nexus.kg.core.contexts.{Context, ContextId, ContextRef, Contexts}
import ch.epfl.bluebrain.nexus.kg.core.domains.DomainRejection.DomainIsDeprecated
import ch.epfl.bluebrain.nexus.kg.core.domains.{DomainId, Domains}
import ch.epfl.bluebrain.nexus.kg.core.organizations.{OrgId, Organizations}
import ch.epfl.bluebrain.nexus.kg.core.schemas.SchemaId
import ch.epfl.bluebrain.nexus.kg.service.BootstrapService.iamClient
import ch.epfl.bluebrain.nexus.kg.service.routes.ContextRoutes.ContextConfig
import ch.epfl.bluebrain.nexus.kg.service.routes.ContextRoutesSpec._
import ch.epfl.bluebrain.nexus.kg.service.routes.Error.classNameOf
import ch.epfl.bluebrain.nexus.sourcing.mem.MemoryAggregate
import ch.epfl.bluebrain.nexus.sourcing.mem.MemoryAggregate._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._
import io.circe.Json
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{Matchers, WordSpecLike}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}

class ContextRoutesSpec
    extends WordSpecLike
    with Matchers
    with ScalatestRouteTest
    with Randomness
    with Resources
    with ScalaFutures
    with MockedIAMClient {

  private implicit val mt: ActorMaterializer        = ActorMaterializer()(system)
  private implicit val ec: ExecutionContextExecutor = system.dispatcher

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(3 seconds, 100 millis)

  "ContextRoutes" should {

    val contextJson = jsonContentOf("/contexts/shacl.json")

    val orgAgg = MemoryAggregate("orgs")(Organizations.initial, Organizations.next, Organizations.eval).toF[Future]
    val orgs   = Organizations(orgAgg)
    val domAgg =
      MemoryAggregate("dom")(Domains.initial, Domains.next, Domains.eval)
        .toF[Future]
    val doms = Domains(domAgg, orgs)
    val ctxAgg =
      MemoryAggregate("contexts")(Contexts.initial, Contexts.next, Contexts.eval)
        .toF[Future]
    val contexts       = Contexts(ctxAgg, doms, baseUri.toString())
    implicit val clock = Clock.systemUTC

    val caller = CallerCtx(clock, AnonymousCaller)
    val orgRef =
      Await.result(orgs.create(OrgId(genString(length = 3)), Json.obj())(caller), 2 seconds)

    val domRef =
      Await.result(doms.create(DomainId(orgRef.id, genString(length = 5)), genString(length = 8))(caller), 2 seconds)

    implicit val cl: IamClient[Future] = iamClient("http://localhost:8080")

    val route = ContextRoutes(contexts, baseUri).routes

    val contextId = ContextId(domRef.id, genString(length = 8), genVersion())

    "create a context" in {
      Put(s"/contexts/${contextId.show}", contextJson) ~> addCredentials(ValidCredentials) ~> route ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json] shouldEqual contextRefAsJson(ContextRef(contextId, 1L))
      }
    }

    "reject the creation of a context that already exists" in {
      Put(s"/contexts/${contextId.show}", contextJson) ~> addCredentials(ValidCredentials) ~> route ~> check {
        status shouldEqual StatusCodes.Conflict
        responseAs[Error].code shouldEqual classNameOf[ContextAlreadyExists.type]
      }
    }

    "reject the creation of a context with illegal version format" in {
      val id = contextId.show.replace(contextId.version.show, "v1.0")
      Put(s"/contexts/$id", contextJson) ~> addCredentials(ValidCredentials) ~> route ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[Error].code shouldEqual classNameOf[IllegalVersionFormat.type]
      }
    }

    "reject the creation of a context with illegal name format" in {
      Put(s"/contexts/${contextId.copy(name = "@!").show}", contextJson) ~> addCredentials(ValidCredentials) ~> route ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[Error].code shouldEqual classNameOf[InvalidContextId.type]
      }
    }

    "return the current context" in {
      Get(s"/contexts/${contextId.show}") ~> addCredentials(ValidCredentials) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual Json
          .obj(
            "@id"        -> Json.fromString(s"$baseUri/contexts/${contextId.show}"),
            "rev"        -> Json.fromLong(1L),
            "deprecated" -> Json.fromBoolean(false),
            "published"  -> Json.fromBoolean(false)
          )
          .deepMerge(contextJson)
      }
    }

    "update a context" in {
      Put(s"/contexts/${contextId.show}?rev=1", contextJson) ~> addCredentials(ValidCredentials) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual contextRefAsJson(ContextRef(contextId, 2L))
      }
    }

    "reject updating a context which doesn't exits" in {
      Put(s"/contexts/${contextId.copy(name = "another").show}?rev=1", contextJson) ~> addCredentials(ValidCredentials) ~> route ~> check {
        status shouldEqual StatusCodes.NotFound
        responseAs[Error].code shouldEqual classNameOf[ContextDoesNotExist.type]
      }
    }

    "reject updating a context with incorrect rev" in {
      Put(s"/contexts/${contextId.show}?rev=10", contextJson) ~> addCredentials(ValidCredentials) ~> route ~> check {
        status shouldEqual StatusCodes.Conflict
        responseAs[Error].code shouldEqual classNameOf[IncorrectRevisionProvided.type]
      }
    }

    "publish a context" in {
      Patch(s"/contexts/${contextId.show}/config?rev=2", ContextConfig(published = true)) ~> addCredentials(
        ValidCredentials) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual contextRefAsJson(ContextRef(contextId, 3L))
      }
      contexts.fetch(contextId).futureValue shouldEqual Some(
        Context(contextId, 3L, contextJson, deprecated = false, published = true))
    }

    "reject updating a context when it is published" in {
      Put(s"/contexts/${contextId.show}?rev=3", contextJson) ~> addCredentials(ValidCredentials) ~> route ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[Error].code shouldEqual classNameOf[CannotUpdatePublished.type]
      }
    }

    "deprecate a context" in {
      Delete(s"/contexts/${contextId.show}?rev=3") ~> addCredentials(ValidCredentials) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual contextRefAsJson(ContextRef(contextId, 4L))
      }
      contexts.fetch(contextId).futureValue shouldEqual Some(
        Context(contextId, 4L, contextJson, deprecated = true, published = true))
    }

    "reject updating a context when it is deprecated" in {
      Put(s"/contexts/${contextId.show}?rev=4", contextJson) ~> addCredentials(ValidCredentials) ~> route ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[Error].code shouldEqual classNameOf[ContextIsDeprecated.type]
      }
    }

    "reject the creation of schema importing non-existing context" in {
      val invalidContextId = ContextId(domRef.id, genString(length = 8), genVersion())
      val invalidContextJson = jsonContentOf(
        "/contexts/importing-context.json",
        Map(
          quote("{{org}}")   -> orgRef.id.id,
          quote("{{dom}}")   -> domRef.id.id,
          quote("{{mixed}}") -> genString(length = 8),
          quote("{{ver}}")   -> genVersion().show
        )
      )
      Put(s"/contexts/${invalidContextId.show}", invalidContextJson) ~> addCredentials(ValidCredentials) ~> route ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[Error].code shouldEqual classNameOf[IllegalImportsViolation.type]
      }
    }

    "reject the creation of context from a deprecated domain" in {
      //Deprecate the domain
      doms.deprecate(domRef.id, domRef.rev)(caller).futureValue
      //Create a SchemaId from the deprecated domain
      val contextId2 = SchemaId(domRef.id, genString(length = 8), genVersion())

      Put(s"/contexts/${contextId2.show}", contextJson) ~> addCredentials(ValidCredentials) ~> route ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[Error].code shouldEqual classNameOf[DomainIsDeprecated.type]
      }
    }

  }
}

object ContextRoutesSpec {

  private val baseUri = Uri("http://localhost/v0")

  private def contextRefAsJson(ref: ContextRef) =
    Json.obj("@id" -> Json.fromString(s"$baseUri/contexts/${ref.id.show}"), "rev" -> Json.fromLong(ref.rev))
}

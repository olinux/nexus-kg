package ch.epfl.bluebrain.nexus.kg.core.schemas

import java.time.Clock
import java.util.regex.Pattern.quote

import cats.instances.try_._
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.commons.iam.identity.Caller.AnonymousCaller
import ch.epfl.bluebrain.nexus.commons.iam.identity.Identity.Anonymous
import ch.epfl.bluebrain.nexus.commons.test._
import ch.epfl.bluebrain.nexus.kg.core.CallerCtx._
import ch.epfl.bluebrain.nexus.kg.core.Fault.CommandRejected
import ch.epfl.bluebrain.nexus.kg.core.contexts.{ContextId, Contexts}
import ch.epfl.bluebrain.nexus.kg.core.domains.DomainRejection.DomainIsDeprecated
import ch.epfl.bluebrain.nexus.kg.core.domains.{DomainId, Domains}
import ch.epfl.bluebrain.nexus.kg.core.organizations.OrgRejection.OrgIsDeprecated
import ch.epfl.bluebrain.nexus.kg.core.organizations.{OrgId, Organizations}
import ch.epfl.bluebrain.nexus.kg.core.schemas.SchemaRejection._
import ch.epfl.bluebrain.nexus.kg.core.schemas.shapes.{Shape, ShapeId}
import ch.epfl.bluebrain.nexus.sourcing.mem.MemoryAggregate
import ch.epfl.bluebrain.nexus.sourcing.mem.MemoryAggregate._
import io.circe.Json
import org.scalatest.{Inspectors, Matchers, TryValues, WordSpecLike}

import scala.util.Try

//noinspection TypeAnnotation
class SchemasSpec extends WordSpecLike with Matchers with Inspectors with TryValues with Randomness with Resources {

  private def genId(): String =
    genString(length = 4, Vector.range('a', 'z') ++ Vector.range('0', '9'))

  private def genJson(): Json =
    Json.obj("key" -> Json.fromString(genString()))

  private def genName(): String =
    genString(length = 8, Vector.range('a', 'z') ++ Vector.range('0', '9'))

  val schemaJson         = jsonContentOf("/int-value-schema.json")
  val optionalSchemaJson = jsonContentOf("/optional-int-value-schema.json")
  val shapeNodeShape     = jsonContentOf("/int-value-shape-nodeshape.json")
  val baseUri            = "http://localhost:8080/v0"

  private implicit val caller = AnonymousCaller(Anonymous())
  private implicit val clock  = Clock.systemUTC

  trait Context {
    val orgsAgg = MemoryAggregate("org")(Organizations.initial, Organizations.next, Organizations.eval).toF[Try]
    val domAgg =
      MemoryAggregate("dom")(Domains.initial, Domains.next, Domains.eval)
        .toF[Try]
    val schemasAgg =
      MemoryAggregate("schemas")(Schemas.initial, Schemas.next, Schemas.eval)
        .toF[Try]
    val ctxsAgg =
      MemoryAggregate("contexts")(Contexts.initial, Contexts.next, Contexts.eval)
        .toF[Try]

    val orgs    = Organizations(orgsAgg)
    val doms    = Domains(domAgg, orgs)
    val ctxs    = Contexts(ctxsAgg, doms, baseUri)
    val schemas = Schemas(schemasAgg, doms, ctxs, baseUri)

    val orgRef = orgs.create(OrgId(genId()), genJson()).success.value
    val domRef =
      doms.create(DomainId(orgRef.id, genId()), "domain").success.value
  }

  "A Schemas instance" should {

    "create a new schema" in new Context {
      val id = SchemaId(domRef.id, genName(), genVersion())
      schemas.create(id, schemaJson).success.value shouldEqual SchemaRef(id, 1L)
      schemas.fetch(id).success.value shouldEqual Some(
        Schema(id, 1L, schemaJson, deprecated = false, published = false))
    }

    "update a new schema" in new Context {
      val id = SchemaId(domRef.id, genName(), genVersion())
      schemas.create(id, schemaJson).success.value shouldEqual SchemaRef(id, 1L)
      schemas
        .update(id, 1L, optionalSchemaJson)
        .success
        .value shouldEqual SchemaRef(id, 2L)
      schemas.fetch(id).success.value shouldEqual Some(
        Schema(id, 2L, optionalSchemaJson, deprecated = false, published = false))
    }

    "publish a schema" in new Context {
      val id = SchemaId(domRef.id, genName(), genVersion())
      schemas.create(id, schemaJson).success.value shouldEqual SchemaRef(id, 1L)
      schemas.publish(id, 1L).success.value shouldEqual SchemaRef(id, 2L)
      schemas.fetch(id).success.value shouldEqual Some(Schema(id, 2L, schemaJson, deprecated = false, published = true))
    }

    "deprecate a schema" in new Context {
      val id = SchemaId(domRef.id, genName(), genVersion())
      schemas.create(id, schemaJson).success.value shouldEqual SchemaRef(id, 1L)
      schemas.deprecate(id, 1L).success.value shouldEqual SchemaRef(id, 2L)
      schemas.fetch(id).success.value shouldEqual Some(Schema(id, 2L, schemaJson, deprecated = true, published = false))
    }

    "deprecate a published schema" in new Context {
      val id = SchemaId(domRef.id, genName(), genVersion())
      schemas.create(id, schemaJson).success.value shouldEqual SchemaRef(id, 1L)
      schemas.publish(id, 1L).success.value shouldEqual SchemaRef(id, 2L)
      schemas.deprecate(id, 2L).success.value shouldEqual SchemaRef(id, 3L)
      schemas.fetch(id).success.value shouldEqual Some(Schema(id, 3L, schemaJson, deprecated = true, published = true))
    }

    "fetch old revision of an schema" in new Context {
      val id = SchemaId(domRef.id, genName(), genVersion())
      schemas.create(id, schemaJson).success
      schemas.update(id, 1L, optionalSchemaJson).success
      schemas.publish(id, 2L).success

      schemas.fetch(id).success.value shouldEqual Some(
        Schema(id, 3L, optionalSchemaJson, deprecated = false, published = true))

      schemas.fetch(id, 2L).success.value shouldEqual Some(
        Schema(id, 2L, optionalSchemaJson, deprecated = false, published = false))

      schemas.fetch(id, 1L).success.value shouldEqual Some(
        Schema(id, 1L, schemaJson, deprecated = false, published = false))
    }

    "fetch old revision of a shape" in new Context {
      val id = SchemaId(domRef.id, genName(), genVersion())
      schemas.create(id, schemaJson).success
      schemas.update(id, 1L, optionalSchemaJson).success
      schemas.publish(id, 2L).success

      schemas.fetchShape(id, "IdNodeShape2").success.value shouldEqual None

      schemas.fetchShape(id, "IdNodeShape2", 1L).success.value shouldEqual
        Some(Shape(ShapeId(id, "IdNodeShape2"), 1L, shapeNodeShape, deprecated = false, published = false))
    }

    "return None when fetching a revision that does not exist" in new Context {
      val id = SchemaId(domRef.id, genName(), genVersion())
      schemas.create(id, schemaJson).success
      schemas.fetch(id, 4L).success.value shouldEqual None
    }

    "fetch specific schema shapes" in new Context {
      val id = SchemaId(domRef.id, genName(), genVersion())
      schemas.create(id, schemaJson).success.value shouldEqual SchemaRef(id, 1L)
      schemas.fetchShape(id, "IdNodeShape2").success.value shouldEqual
        Some(Shape(ShapeId(id, "IdNodeShape2"), 1L, shapeNodeShape, deprecated = false, published = false))
    }

    "expand the context of the schema during validation" in new Context {
      val ctx =
        ctxs.create(ContextId(domRef.id, genName(), genVersion()), jsonContentOf("/contexts/shacl.json")).success.value
      ctxs.publish(ctx.id, ctx.rev).success
      val ctxReplacements = Map(quote("{{context}}") -> s"$baseUri/contexts/${ctx.id.show}")
      schemas
        .create(SchemaId(domRef.id, genName(), genVersion()),
                jsonContentOf("/importing-int-value-schema.json", ctxReplacements))
        .success
    }

    "prevent from fetching a non existing shape" in new Context {
      val id = SchemaId(domRef.id, genName(), genVersion())
      schemas.create(id, schemaJson).success.value shouldEqual SchemaRef(id, 1L)
      schemas.fetchShape(id, "IdNode").success.value shouldEqual None
    }

    "prevent from fetching a shape with a non existing revision" in new Context {
      val id = SchemaId(domRef.id, genName(), genVersion())
      schemas.create(id, schemaJson).success.value
      schemas.fetchShape(id, "IdNodeShape2", 4L).success.value shouldEqual None
    }

    "prevent double deprecations" in new Context {
      val id = SchemaId(domRef.id, genName(), genVersion())
      schemas.create(id, schemaJson).success.value shouldEqual SchemaRef(id, 1L)
      schemas.deprecate(id, 1L).success.value shouldEqual SchemaRef(id, 2L)
      schemas.deprecate(id, 2L).failure.exception shouldEqual CommandRejected(SchemaIsDeprecated)
    }

    "prevent publishing when deprecated" in new Context {
      val id = SchemaId(domRef.id, genName(), genVersion())
      schemas.create(id, schemaJson).success.value shouldEqual SchemaRef(id, 1L)
      schemas.deprecate(id, 1L).success.value shouldEqual SchemaRef(id, 2L)
      schemas.publish(id, 2L).failure.exception shouldEqual CommandRejected(SchemaIsDeprecated)
    }

    "prevent duplicate publish" in new Context {
      val id = SchemaId(domRef.id, genName(), genVersion())
      schemas.create(id, schemaJson).success.value shouldEqual SchemaRef(id, 1L)
      schemas.publish(id, 1L).success.value shouldEqual SchemaRef(id, 2L)
      schemas.publish(id, 2L).failure.exception shouldEqual CommandRejected(CannotUpdatePublished)
    }

    "prevent update when deprecated" in new Context {
      val id = SchemaId(domRef.id, genName(), genVersion())
      schemas.create(id, schemaJson).success.value shouldEqual SchemaRef(id, 1L)
      schemas.deprecate(id, 1L).success.value shouldEqual SchemaRef(id, 2L)
      schemas
        .update(id, 2L, schemaJson)
        .failure
        .exception shouldEqual CommandRejected(SchemaIsDeprecated)
    }

    "prevent update when published" in new Context {
      val id = SchemaId(domRef.id, genName(), genVersion())
      schemas.create(id, schemaJson).success.value shouldEqual SchemaRef(id, 1L)
      schemas.publish(id, 1L).success.value shouldEqual SchemaRef(id, 2L)
      schemas
        .update(id, 2L, schemaJson)
        .failure
        .exception shouldEqual CommandRejected(CannotUpdatePublished)
    }

    "prevent update with incorrect rev" in new Context {
      val id = SchemaId(domRef.id, genName(), genVersion())
      schemas.create(id, schemaJson).success.value shouldEqual SchemaRef(id, 1L)
      schemas
        .update(id, 2L, schemaJson)
        .failure
        .exception shouldEqual CommandRejected(IncorrectRevisionProvided)
    }

    "prevent deprecate with incorrect rev" in new Context {
      val id = SchemaId(domRef.id, genName(), genVersion())
      schemas.create(id, schemaJson).success.value shouldEqual SchemaRef(id, 1L)
      schemas.deprecate(id, 2L).failure.exception shouldEqual CommandRejected(IncorrectRevisionProvided)
    }

    "prevent publish with incorrect rev" in new Context {
      val id = SchemaId(domRef.id, genName(), genVersion())
      schemas.create(id, schemaJson).success.value shouldEqual SchemaRef(id, 1L)
      schemas.publish(id, 2L).failure.exception shouldEqual CommandRejected(IncorrectRevisionProvided)
    }

    "return None for a schema that doesn't exist" in new Context {
      val id = SchemaId(domRef.id, genName(), genVersion())
      schemas.fetch(id).success.value shouldEqual None
    }

    "prevent creation with invalid schema" in new Context {
      val id    = SchemaId(domRef.id, genName(), genVersion())
      val value = genJson()
      schemas.create(id, value).failure.exception match {
        case CommandRejected(ShapeConstraintViolations(vs)) =>
          vs should not be empty
        case _ => fail("schema creation with invalid schema was not rejected")
      }
    }

    "prevent schema update with invalid schema" in new Context {
      val id    = SchemaId(domRef.id, genName(), genVersion())
      val value = genJson()
      schemas.create(id, schemaJson).success.value shouldEqual SchemaRef(id, 1L)
      schemas.update(id, 1L, value).failure.exception match {
        case CommandRejected(ShapeConstraintViolations(vs)) =>
          vs should not be empty
        case _ => fail("schema creation with invalid schema was not rejected")
      }
    }
  }

  trait DomainDeprecatedContext extends Context {
    val lockedId = SchemaId(domRef.id, genName(), genVersion())
    val locked   = schemas.create(lockedId, schemaJson).success.value
    val _        = doms.deprecate(domRef.id, domRef.rev).success.value
  }

  trait OrgDeprecatedContext extends Context {
    val lockedId = SchemaId(domRef.id, genName(), genVersion())
    val locked   = schemas.create(lockedId, schemaJson).success.value
    val _        = orgs.deprecate(orgRef.id, orgRef.rev).success.value
  }

  "A Schemas" when {

    "domain is deprecated" should {

      "prevent updates" in new DomainDeprecatedContext {
        schemas
          .update(lockedId, 1L, genJson())
          .failure
          .exception shouldEqual CommandRejected(DomainIsDeprecated)
      }

      "prevent publishing" in new DomainDeprecatedContext {
        schemas
          .publish(lockedId, 1L)
          .failure
          .exception shouldEqual CommandRejected(DomainIsDeprecated)
      }

      "allow deprecation" in new DomainDeprecatedContext {
        schemas.deprecate(lockedId, 1L).success.value shouldEqual SchemaRef(lockedId, 2L)
        schemas.fetch(lockedId).success.value shouldEqual Some(
          Schema(lockedId, 2L, schemaJson, deprecated = true, published = false))
      }

      "prevent new schema creation" in new DomainDeprecatedContext {
        val id = SchemaId(domRef.id, genName(), genVersion())
        schemas
          .create(id, schemaJson)
          .failure
          .exception shouldEqual CommandRejected(DomainIsDeprecated)
      }
    }

    "organization is deprecated" should {

      "prevent updates" in new OrgDeprecatedContext {
        schemas
          .update(lockedId, 1L, genJson())
          .failure
          .exception shouldEqual CommandRejected(OrgIsDeprecated)
      }

      "prevent publishing" in new OrgDeprecatedContext {
        schemas
          .publish(lockedId, 1L)
          .failure
          .exception shouldEqual CommandRejected(OrgIsDeprecated)
      }

      "allow deprecation" in new OrgDeprecatedContext {
        schemas.deprecate(lockedId, 1L).success.value shouldEqual SchemaRef(lockedId, 2L)
        schemas.fetch(lockedId).success.value shouldEqual Some(
          Schema(lockedId, 2L, schemaJson, deprecated = true, published = false))
      }

      "prevent new schema creation" in new OrgDeprecatedContext {
        val id = SchemaId(domRef.id, genName(), genVersion())
        schemas
          .create(id, schemaJson)
          .failure
          .exception shouldEqual CommandRejected(OrgIsDeprecated)
      }
    }
  }
}

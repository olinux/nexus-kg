package ch.epfl.bluebrain.nexus.kg.indexing.contexts

import cats.instances.string._
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.commons.iam.acls.Meta
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlClient
import ch.epfl.bluebrain.nexus.kg.core.contexts.ContextEvent.{
  ContextCreated,
  ContextDeprecated,
  ContextPublished,
  ContextUpdated
}
import ch.epfl.bluebrain.nexus.kg.core.contexts.{ContextEvent, ContextId, ContextName}
import ch.epfl.bluebrain.nexus.kg.core.domains.DomainId
import ch.epfl.bluebrain.nexus.kg.core.organizations.OrgId
import ch.epfl.bluebrain.nexus.kg.indexing.IndexingVocab.JsonLDKeys._
import ch.epfl.bluebrain.nexus.kg.indexing.IndexingVocab.PrefixMapping._
import ch.epfl.bluebrain.nexus.commons.http.JsonOps._
import ch.epfl.bluebrain.nexus.kg.indexing.Qualifier._
import ch.epfl.bluebrain.nexus.kg.indexing.jsonld.IndexJsonLdSupport._
import ch.epfl.bluebrain.nexus.kg.indexing.query.PatchQuery
import ch.epfl.bluebrain.nexus.kg.indexing.{ConfiguredQualifier, Qualifier}
import io.circe.Json
import journal.Logger

class ContextIndexer[F[_]](client: SparqlClient[F], settings: ContextIndexingSettings) {

  private val log                                                   = Logger[this.type]
  private val ContextIndexingSettings(index, base, baseNs, baseVoc) = settings

  private implicit val orgIdQualifier: ConfiguredQualifier[OrgId]             = Qualifier.configured[OrgId](base)
  private implicit val domainIdQualifier: ConfiguredQualifier[DomainId]       = Qualifier.configured[DomainId](base)
  private implicit val contextNameQualifier: ConfiguredQualifier[ContextName] = Qualifier.configured[ContextName](base)
  private implicit val contextIdQualifier: ConfiguredQualifier[ContextId]     = Qualifier.configured[ContextId](base)
  private implicit val stringQualifier: ConfiguredQualifier[String]           = Qualifier.configured[String](baseVoc)

  private val revKey        = "rev".qualifyAsString
  private val deprecatedKey = "deprecated".qualifyAsString
  private val publishedKey  = "published".qualifyAsString
  private val orgKey        = "organization".qualifyAsString
  private val domainKey     = "domain".qualifyAsString
  private val nameKey       = "name".qualifyAsString
  private val versionKey    = "version".qualifyAsString

  final def apply(event: ContextEvent): F[Unit] = event match {
    case ContextCreated(id, rev, m, value) =>
      log.debug(s"Indexing 'ContextCreated' event for id '${id.show}'")
      val meta = buildMeta(id, rev, m, deprecated = Some(false), published = Some(false))
      val data = value removeKeys ("links") deepMerge meta
      client.createGraph(index, id qualifyWith baseNs, data deepMerge Json.obj(createdAtTimeKey -> m.instant.jsonLd))

    case ContextUpdated(id, rev, m, value) =>
      log.debug(s"Indexing 'ContextUpdated' event for id '${id.show}'")
      val meta        = buildMeta(id, rev, m, deprecated = Some(false), published = Some(false))
      val data        = value removeKeys ("links") deepMerge meta
      val removeQuery = PatchQuery.inverse(id qualifyWith baseNs, createdAtTimeKey)
      client.patchGraph(index, id qualifyWith baseNs, removeQuery, data)

    case ContextPublished(id, rev, m) =>
      log.debug(s"Indexing 'ContextPublished' event for id '${id.show}'")
      val meta = buildMeta(id, rev, m, deprecated = None, published = Some(true)) deepMerge Json.obj(
        publishedAtTimeKey -> m.instant.jsonLd)
      val removeQuery = PatchQuery(id, id qualifyWith baseNs, revKey, publishedKey, updatedAtTimeKey)
      client.patchGraph(index, id qualifyWith baseNs, removeQuery, meta)

    case ContextDeprecated(id, rev, m) =>
      log.debug(s"Indexing 'ContextDeprecated' event for id '${id.show}'")
      val meta        = buildMeta(id, rev, m, deprecated = Some(true), published = None)
      val removeQuery = PatchQuery(id, id qualifyWith baseNs, revKey, deprecatedKey, updatedAtTimeKey)
      client.patchGraph(index, id qualifyWith baseNs, removeQuery, meta)
  }

  private def buildMeta(id: ContextId,
                        rev: Long,
                        meta: Meta,
                        deprecated: Option[Boolean],
                        published: Option[Boolean]): Json = {
    val sharedObj = Json.obj(
      idKey            -> Json.fromString(id.qualifyAsString),
      revKey           -> Json.fromLong(rev),
      orgKey           -> id.domainId.orgId.qualify.jsonLd,
      domainKey        -> id.domainId.qualify.jsonLd,
      nameKey          -> Json.fromString(id.name),
      versionKey       -> Json.fromString(id.version.show),
      contextGroupKey  -> id.contextName.qualify.jsonLd,
      updatedAtTimeKey -> meta.instant.jsonLd,
      rdfTypeKey       -> "Context".qualify.jsonLd
    )

    val publishedObj = published
      .map(v => Json.obj(publishedKey -> Json.fromBoolean(v)))
      .getOrElse(Json.obj())

    val deprecatedObj = deprecated
      .map(v => Json.obj(deprecatedKey -> Json.fromBoolean(v)))
      .getOrElse(Json.obj())

    deprecatedObj deepMerge publishedObj deepMerge sharedObj
  }

}

object ContextIndexer {
  final def apply[F[_]](client: SparqlClient[F], settings: ContextIndexingSettings): ContextIndexer[F] =
    new ContextIndexer[F](client, settings)
}

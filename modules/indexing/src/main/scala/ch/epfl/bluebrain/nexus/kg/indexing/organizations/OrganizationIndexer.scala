package ch.epfl.bluebrain.nexus.kg.indexing.organizations

import cats.MonadError
import cats.instances.string._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.commons.iam.acls.Meta
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlClient
import ch.epfl.bluebrain.nexus.kg.core.contexts.Contexts
import ch.epfl.bluebrain.nexus.kg.core.organizations.OrgEvent.{OrgCreated, OrgDeprecated, OrgUpdated}
import ch.epfl.bluebrain.nexus.kg.core.organizations.{OrgEvent, OrgId}
import ch.epfl.bluebrain.nexus.kg.indexing.IndexingVocab.JsonLDKeys._
import ch.epfl.bluebrain.nexus.kg.indexing.IndexingVocab.PrefixMapping
import ch.epfl.bluebrain.nexus.kg.indexing.IndexingVocab.PrefixMapping._
import ch.epfl.bluebrain.nexus.commons.http.JsonOps._
import ch.epfl.bluebrain.nexus.kg.indexing.Qualifier._
import ch.epfl.bluebrain.nexus.kg.indexing.jsonld.IndexJsonLdSupport._
import ch.epfl.bluebrain.nexus.kg.indexing.query.PatchQuery
import ch.epfl.bluebrain.nexus.kg.indexing.{ConfiguredQualifier, Qualifier}
import io.circe.Json
import journal.Logger

/**
  * Organization incremental indexing logic that pushes data into an rdf triple store.
  *
  * @param client   the SPARQL client to use for communicating with the rdf triple store
  * @param contexts  the context operation bundle
  * @param settings the indexing settings
  * @tparam F the monadic effect type
  */
class OrganizationIndexer[F[_]](client: SparqlClient[F], contexts: Contexts[F], settings: OrganizationIndexingSettings)(
    implicit F: MonadError[F, Throwable]) {

  private val log                                                        = Logger[this.type]
  private val OrganizationIndexingSettings(index, base, baseNs, baseVoc) = settings

  private implicit val orgIdQualifier: ConfiguredQualifier[OrgId]   = Qualifier.configured[OrgId](base)
  private implicit val stringQualifier: ConfiguredQualifier[String] = Qualifier.configured[String](baseVoc)

  private val revKey        = "rev".qualifyAsString
  private val deprecatedKey = "deprecated".qualifyAsString
  private val orgName       = "name".qualifyAsString

  /**
    * Indexes the event by pushing it's json ld representation into the rdf triple store while also updating the
    * existing triples.
    *
    * @param event the event to index
    * @return a Unit value in the ''F[_]'' context
    */
  final def apply(event: OrgEvent): F[Unit] = event match {
    case OrgCreated(id, rev, m, value) =>
      log.debug(s"Indexing 'OrgCreated' event for id '${id.show}'")
      val meta = buildMeta(id, rev, m, deprecated = Some(false))
      contexts
        .expand(value removeKeys ("links"))
        .map(_ deepMerge meta)
        .flatMap { json =>
          client
            .createGraph(index, id qualifyWith baseNs, json deepMerge Json.obj(createdAtTimeKey -> m.instant.jsonLd))
        }

    case OrgUpdated(id, rev, m, value) =>
      log.debug(s"Indexing 'OrgUpdated' event for id '${id.show}'")
      val meta = buildMeta(id, rev, m, deprecated = Some(false))
      contexts
        .expand(value removeKeys ("links"))
        .map(_ deepMerge meta)
        .flatMap { json =>
          val removeQuery = PatchQuery.inverse(id qualifyWith baseNs, createdAtTimeKey)
          client.patchGraph(index, id qualifyWith baseNs, removeQuery, json)
        }

    case OrgDeprecated(id, rev, m) =>
      log.debug(s"Indexing 'OrgDeprecated' event for id '${id.show}'")
      val meta        = buildMeta(id, rev, m, deprecated = Some(true))
      val removeQuery = PatchQuery(id, id qualifyWith baseNs, revKey, deprecatedKey, updatedAtTimeKey)
      client.patchGraph(index, id qualifyWith baseNs, removeQuery, meta)
  }

  private def buildMeta(id: OrgId, rev: Long, meta: Meta, deprecated: Option[Boolean]): Json = {
    val sharedObj = Json.obj(
      idKey                    -> Json.fromString(id.qualifyAsString),
      revKey                   -> Json.fromLong(rev),
      orgName                  -> Json.fromString(id.id),
      updatedAtTimeKey         -> meta.instant.jsonLd,
      PrefixMapping.rdfTypeKey -> "Organization".qualify.jsonLd
    )

    val deprecatedObj = deprecated
      .map(v => Json.obj(deprecatedKey -> Json.fromBoolean(v)))
      .getOrElse(Json.obj())

    deprecatedObj deepMerge sharedObj
  }
}

object OrganizationIndexer {

  /**
    * Constructs a organization incremental indexer that pushes data into an rdf triple store.
    *
    * @param client   the SPARQL client to use for communicating with the rdf triple store
    * @param contexts  the context operation bundle
    * @param settings the indexing settings
    * @tparam F the monadic effect type
    */
  final def apply[F[_]](client: SparqlClient[F], contexts: Contexts[F], settings: OrganizationIndexingSettings)(
      implicit F: MonadError[F, Throwable]): OrganizationIndexer[F] =
    new OrganizationIndexer[F](client, contexts, settings)
}

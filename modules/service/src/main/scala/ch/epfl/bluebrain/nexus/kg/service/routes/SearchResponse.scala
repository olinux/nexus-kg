package ch.epfl.bluebrain.nexus.kg.service.routes

import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Directives.{complete, extract, onSuccess}
import akka.http.scaladsl.server.Route
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport.OrderedKeys
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlCirceSupport._
import ch.epfl.bluebrain.nexus.kg.indexing.pagination.Pagination
import ch.epfl.bluebrain.nexus.kg.indexing.query.QueryResult.{ScoredQueryResult, UnscoredQueryResult}
import ch.epfl.bluebrain.nexus.kg.indexing.query.{QueryResult, QueryResults}
import ch.epfl.bluebrain.nexus.kg.service.hateoas.Links
import ch.epfl.bluebrain.nexus.kg.service.io.PrinterSettings._
import ch.epfl.bluebrain.nexus.kg.service.query.LinksQueryResults
import io.circe.Encoder
import io.circe.generic.auto._

import scala.concurrent.{ExecutionContext, Future}

trait SearchResponse {

  /**
    * Syntactic sugar for constructing a [[Route]] from the [[QueryResults]]
    */
  implicit class QueryResultsOpts[Id](qr: Future[QueryResults[Id]]) {

    private[routes] def addPagination(base: Uri, pagination: Pagination)(implicit
                                                                         L: Encoder[Links],
                                                                         R: Encoder[UnscoredQueryResult[Id]],
                                                                         S: Encoder[ScoredQueryResult[Id]],
                                                                         orderedKeys: OrderedKeys): Route = {
      extract(_.request.uri) { uri =>
        onSuccess(qr) { result =>
          val lqu = base.copy(path = uri.path, fragment = uri.fragment, rawQueryString = uri.rawQueryString)
          complete(StatusCodes.OK -> LinksQueryResults(result, pagination, lqu))
        }
      }
    }

    /**
      * Interface syntax to expose new functionality into [[QueryResults]] response type.
      * Decides if either a generic type ''Id'' or ''Entity'' should be used.
      *
      * @param fields     the fields query parameters
      * @param base       the service public uri + prefix
      * @param pagination the pagination values
      */
    def buildResponse[Entity](fields: Set[String], base: Uri, pagination: Pagination)(
        implicit
        f: Id => Future[Option[Entity]],
        ec: ExecutionContext,
        L: Encoder[Links],
        R: Encoder[UnscoredQueryResult[Id]],
        S: Encoder[ScoredQueryResult[Id]],
        Re: Encoder[UnscoredQueryResult[Entity]],
        Se: Encoder[ScoredQueryResult[Entity]],
        orderedKeys: OrderedKeys): Route = {
      if (fields.contains("all")) {
        qr.flatMap { q =>
            q.results
              .foldLeft(Future(List.empty[QueryResult[Entity]])) { (acc, current) =>
                f(current.source) flatMap {
                  case Some(instance) => acc.map(list => current.map(_ => instance) :: list)
                  case None           => acc
                }
              }
              .map(list => q.copyWith(list.reverse))
          }
          .addPagination(base, pagination)
      } else {
        qr.addPagination(base, pagination)
      }
    }
  }

}

object SearchResponse extends SearchResponse

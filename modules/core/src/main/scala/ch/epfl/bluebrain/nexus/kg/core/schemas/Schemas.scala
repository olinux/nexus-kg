package ch.epfl.bluebrain.nexus.kg.core.schemas

import cats.MonadError
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import cats.syntax.applicativeError._
import ch.epfl.bluebrain.nexus.commons.shacl.validator.ShaclValidatorErr.{CouldNotFindImports, IllegalImportDefinition}
import ch.epfl.bluebrain.nexus.commons.shacl.validator.{ShaclSchema, ShaclValidator}
import ch.epfl.bluebrain.nexus.kg.core.CallerCtx
import ch.epfl.bluebrain.nexus.kg.core.Fault.{CommandRejected, Unexpected}
import ch.epfl.bluebrain.nexus.kg.core.contexts.{ContextName, Contexts}
import ch.epfl.bluebrain.nexus.kg.core.domains.Domains
import ch.epfl.bluebrain.nexus.kg.core.schemas.SchemaCommand._
import ch.epfl.bluebrain.nexus.kg.core.schemas.SchemaEvent._
import ch.epfl.bluebrain.nexus.kg.core.schemas.SchemaRejection._
import ch.epfl.bluebrain.nexus.kg.core.schemas.SchemaState._
import ch.epfl.bluebrain.nexus.kg.core.schemas.Schemas.SchemaAggregate
import ch.epfl.bluebrain.nexus.kg.core.schemas.shapes.{Shape, ShapeId}
import ch.epfl.bluebrain.nexus.sourcing.Aggregate
import io.circe.Json
import journal.Logger

/**
  * Bundles operations that can be performed against schemas using the underlying persistence abstraction.
  *
  * @param agg     the aggregate definition
  * @param doms    the domains operations bundle
  * @param ctxs    the contexts operations bundle
  * @param baseUri the base uri of the service
  * @param F       a MonadError typeclass instance for ''F[_]''
  * @tparam F the monadic effect type
  */
final class Schemas[F[_]](agg: SchemaAggregate[F], doms: Domains[F], ctxs: Contexts[F], baseUri: String)(
    implicit F: MonadError[F, Throwable]) { self =>

  private val logger = Logger[this.type]

  private val validator = ShaclValidator[F](SchemaImportResolver[F](baseUri, self.fetch))

  private def validateId(id: SchemaId): F[Unit] = {
    logger.debug(s"Validating id '$id'")
    val regex = ContextName.regex
    id.schemaName.show match {
      case regex(_, _, _) =>
        logger.debug(s"Id validation for '$id' succeeded")
        F.pure(())
      case _ =>
        logger.debug(s"Id validation for '$id' failed, name '${id.schemaName.show}' did not match regex '$regex'")
        F.raiseError(CommandRejected(InvalidSchemaId(id)))
    }
  }

  /**
    * Asserts the domain exists and allows modifications on children resources.
    *
    * @param id the id of the domain
    * @return () or the appropriate rejection in the ''F'' context
    */
  def assertUnlocked(id: SchemaId): F[Unit] = {
    val result = for {
      _     <- doms.assertUnlocked(id.domainId)
      state <- agg.currentState(id.show)
    } yield state

    result flatMap {
      case Initial                    => F.raiseError(CommandRejected(SchemaDoesNotExist))
      case c: Current if c.deprecated => F.raiseError(CommandRejected(SchemaIsDeprecated))
      case c: Current if !c.published => F.raiseError(CommandRejected(SchemaIsNotPublished))
      case _                          => F.pure(())
    }
  }

  private def validatePayload(json: Json): F[Unit] =
    ctxs.expand(json).flatMap { expanded =>
      validator(ShaclSchema(expanded))
        .flatMap { report =>
          if (report.conforms) F.pure(())
          else F.raiseError[Unit](CommandRejected(ShapeConstraintViolations(report.result.map(_.reason))))
        }
        .recoverWith {
          case CouldNotFindImports(missing)     => F.raiseError(CommandRejected(MissingImportsViolation(missing)))
          case IllegalImportDefinition(missing) => F.raiseError(CommandRejected(IllegalImportsViolation(missing)))
        }
    }

  private def evaluate(cmd: SchemaCommand, intent: => String): F[Current] = {
    F.pure {
      logger.debug(s"$intent: evaluating command '$cmd'")
    } flatMap { _ =>
      agg.eval(cmd.id.show, cmd)
    } flatMap {
      case Left(rejection) =>
        logger.debug(s"$intent: command '$cmd' was rejected due to '$rejection'")
        F.raiseError(CommandRejected(rejection))
      // $COVERAGE-OFF$
      case Right(s @ Initial) =>
        logger.error(s"$intent: command '$cmd' evaluation failed, received an '$s' state")
        F.raiseError(Unexpected(s"Unexpected Initial state as outcome of evaluating command '$cmd'"))
      // $COVERAGE-ON$
      case Right(state: Current) =>
        logger.debug(s"$intent: command '$cmd' evaluation succeeded, generated state: '$state'")
        F.pure(state)
    }
  }

  /**
    * Creates a new schema instance.  The schema id passed as an argument must contain an ''alphanumeric''  name with a
    * length between ''2'' and ''32'' characters (inclusive).
    *
    * @param id    the unique identifier of the schema
    * @param value the json representation of the schema
    * @return an [[ch.epfl.bluebrain.nexus.kg.core.schemas.SchemaRef]] instance wrapped in the abstract ''F[_]'' type
    *         if successful, or a [[ch.epfl.bluebrain.nexus.kg.core.Fault]] wrapped within ''F[_]'' otherwise
    */
  def create(id: SchemaId, value: Json)(implicit ctx: CallerCtx): F[SchemaRef] =
    for {
      _ <- validateId(id)
      _ <- doms.assertUnlocked(id.domainId)
      _ <- validatePayload(value)
      r <- evaluate(CreateSchema(id, ctx.meta, value), "Create schema")
    } yield SchemaRef(id, r.rev)

  /**
    * Updates an existing schema instance with a new json representation.  Updates can be performed only if the schema
    * instance is not in a published or deprecated status.
    *
    * @param id    the unique identifier of the schema
    * @param rev   the last known revision of the schema instance
    * @param value the json representation of the schema
    * @return an [[ch.epfl.bluebrain.nexus.kg.core.schemas.SchemaRef]] instance wrapped in the abstract ''F[_]'' type
    *         if successful, or a [[ch.epfl.bluebrain.nexus.kg.core.Fault]] wrapped within ''F[_]'' otherwise
    */
  def update(id: SchemaId, rev: Long, value: Json)(implicit ctx: CallerCtx): F[SchemaRef] =
    for {
      _ <- doms.assertUnlocked(id.domainId)
      _ <- validatePayload(value)
      r <- evaluate(UpdateSchema(id, rev, ctx.meta, value), "Update schema")
    } yield SchemaRef(id, r.rev)

  /**
    * Publishes a schema allowing instances conforming to its definition to be created.
    *
    * @param id  the unique identifier of the schema
    * @param rev the last known revision of the schema instance
    * @return an [[ch.epfl.bluebrain.nexus.kg.core.schemas.SchemaRef]] instance wrapped in the abstract ''F[_]'' type
    *         if successful, or a [[ch.epfl.bluebrain.nexus.kg.core.Fault]] wrapped within ''F[_]'' otherwise
    */
  def publish(id: SchemaId, rev: Long)(implicit ctx: CallerCtx): F[SchemaRef] =
    for {
      _ <- doms.assertUnlocked(id.domainId)
      r <- evaluate(PublishSchema(id, rev, ctx.meta), "Publish schema")
    } yield SchemaRef(id, r.rev)

  /**
    * Deprecates a schema locking it for further changes and blocking any attempts to create instances conforming to its
    * definition.
    *
    * @param id  the unique identifier of the schema
    * @param rev the last known revision of the schema instance
    * @return an [[ch.epfl.bluebrain.nexus.kg.core.schemas.SchemaRef]] instance wrapped in the abstract ''F[_]'' type
    *         if successful, or a [[ch.epfl.bluebrain.nexus.kg.core.Fault]] wrapped within ''F[_]'' otherwise
    */
  def deprecate(id: SchemaId, rev: Long)(implicit ctx: CallerCtx): F[SchemaRef] =
    evaluate(DeprecateSchema(id, rev, ctx.meta), "Deprecate schema")
      .map(r => SchemaRef(id, r.rev))

  /**
    * Queries the system for the schema identified by the argument id.  The (in)existence of the schema is represented
    * by the [[scala.Option]] type wrapped within the ''F[_]'' context.
    *
    * @param id the unique identifier of the schema
    * @return an optional [[ch.epfl.bluebrain.nexus.kg.core.schemas.Schema]] instance wrapped in the
    *         abstract ''F[_]'' type if successful, or a [[ch.epfl.bluebrain.nexus.kg.core.Fault]] wrapped within
    *         ''F[_]'' otherwise
    */
  def fetch(id: SchemaId): F[Option[Schema]] =
    agg.currentState(id.show).map {
      case Initial                                          => None
      case Current(_, rev, _, value, published, deprecated) => Some(Schema(id, rev, value, deprecated, published))
    }

  /**
    * Queries the system for the schema identified by the argument id at the specified revision.  The (in)existence of
    * the schema or the requested revision is represented by the [[scala.Option]] type wrapped within the ''F[_]''
    * context.
    *
    * @param id  the unique identifier of the schema
    * @param rev the revision attempted to be fetched
    * @return an optional [[ch.epfl.bluebrain.nexus.kg.core.schemas.Schema]] schema wrapped in the
    *         abstract ''F[_]'' type if successful, or a [[ch.epfl.bluebrain.nexus.kg.core.Fault]] wrapped within
    *         ''F[_]'' otherwise
    */
  def fetch(id: SchemaId, rev: Long): F[Option[Schema]] =
    stateAt(id, rev).map {
      case c: Current if c.rev == rev => Some(Schema(c.id, rev, c.value, c.deprecated, c.published))
      case _                          => None
    }

  private def stateAt(id: SchemaId, rev: Long): F[SchemaState] =
    agg.foldLeft[SchemaState](id.show, Initial) {
      case (state, ev) if ev.rev <= rev => Schemas.next(state, ev)
      case (state, _)                   => state
    }

  /**
    * Queries the system for a particular shape (on a schema identified by the argument id).
    * The (in)existence of the shape is represented by the [[scala.Option]] type wrapped within the ''F[_]'' context.
    *
    * @param id       the unique identifier of the schema
    * @param fragment the partial identifier of the shape (on its @id field)
    * @return an optional [[ch.epfl.bluebrain.nexus.kg.core.schemas.shapes.Shape]] shape wrapped in the
    *         abstract ''F[_]'' type if successful, or a [[ch.epfl.bluebrain.nexus.kg.core.Fault]] wrapped within
    *         ''F[_]'' otherwise
    */
  def fetchShape(id: SchemaId, fragment: String): F[Option[Shape]] = {
    import ch.epfl.bluebrain.nexus.kg.core.circe.CirceShapeExtractorInstances._
    agg.currentState(id.show).map {
      case Initial =>
        None
      case Current(schemaId, rev, _, value, published, deprecated) =>
        value.fetchShape(fragment) match {
          case Some(shapeValue) => Some(Shape(ShapeId(schemaId, fragment), rev, shapeValue, deprecated, published))
          case _                => None
        }
    }
  }

  /**
    * Queries the system for a particular shape's revision (on a schema identified by the argument id).
    * The (in)existence of the shape's revision is represented by the [[scala.Option]] type wrapped within the ''F[_]'' context.
    *
    * @param id       the unique identifier of the schema
    * @param fragment the partial identifier of the shape (on its @id field)
    * @return an optional [[ch.epfl.bluebrain.nexus.kg.core.schemas.shapes.Shape]] shape wrapped in the
    *         abstract ''F[_]'' type if successful, or a [[ch.epfl.bluebrain.nexus.kg.core.Fault]] wrapped within
    *         ''F[_]'' otherwise
    */
  def fetchShape(id: SchemaId, fragment: String, rev: Long): F[Option[Shape]] = {
    import ch.epfl.bluebrain.nexus.kg.core.circe.CirceShapeExtractorInstances._
    stateAt(id, rev).map {
      case Current(schemaId, `rev`, _, value, published, deprecated) =>
        value.fetchShape(fragment) match {
          case Some(shapeValue) => Some(Shape(ShapeId(schemaId, fragment), rev, shapeValue, deprecated, published))
          case _                => None
        }
      case _ => None
    }
  }
}

object Schemas {

  /**
    * Aggregate type definition for Schemas.
    *
    * @tparam F the aggregate effect type
    */
  type SchemaAggregate[F[_]] = Aggregate[F] {
    type Identifier = String
    type Event      = SchemaEvent
    type State      = SchemaState
    type Command    = SchemaCommand
    type Rejection  = SchemaRejection
  }

  /**
    * Constructs a new ''Schemas'' instance that bundles operations that can be performed against schemas using the
    * underlying persistence abstraction.
    *
    * @param agg     the aggregate definition
    * @param doms    the domains operations bundle
    * @param ctxs    the contexts operations bundle
    * @param baseUri the base uri of the service
    * @param F       a MonadError typeclass instance for ''F[_]''
    * @tparam F the monadic effect type
    */
  final def apply[F[_]](agg: SchemaAggregate[F], doms: Domains[F], ctxs: Contexts[F], baseUri: String)(
      implicit F: MonadError[F, Throwable]): Schemas[F] =
    new Schemas[F](agg, doms, ctxs, baseUri)

  /**
    * The initial state of a schema.
    */
  final val initial: SchemaState = Initial

  /**
    * State transition function for schemas; considering a current state (the ''state'' argument) and an emitted
    * ''event'' it computes the next state.
    *
    * @param state the current state
    * @param event the emitted event
    * @return the next state
    */
  final def next(state: SchemaState, event: SchemaEvent): SchemaState = (state, event) match {
    case (Initial, SchemaCreated(id, rev, meta, value)) =>
      Current(id, rev, meta, value, published = false, deprecated = false)
    // $COVERAGE-OFF$
    case (Initial, _) => Initial
    // $COVERAGE-ON$
    case (c: Current, SchemaDeprecated(_, rev, meta)) if c.published =>
      c.copy(rev = rev, meta = meta, deprecated = true)
    case (c: Current, _) if c.deprecated || c.published   => c
    case (c: Current, _: SchemaCreated)                   => c
    case (c: Current, SchemaUpdated(_, rev, meta, value)) => c.copy(rev = rev, meta = meta, value = value)
    case (c: Current, SchemaPublished(_, rev, meta))      => c.copy(rev = rev, meta = meta, published = true)
    case (c: Current, SchemaDeprecated(_, rev, meta))     => c.copy(rev = rev, meta = meta, deprecated = true)
  }

  /**
    * Command evaluation logic for schemas; considering a current ''state'' and a command to be evaluated either
    * reject the command or emit a new event that characterizes the change for an aggregate.
    *
    * @param state the current state
    * @param cmd   the command to be evaluated
    * @return either a rejection or emit an event
    */
  final def eval(state: SchemaState, cmd: SchemaCommand): Either[SchemaRejection, SchemaEvent] = {
    def createSchema(c: CreateSchema) = state match {
      case Initial    => Right(SchemaCreated(c.id, 1L, c.meta, c.value))
      case _: Current => Left(SchemaAlreadyExists)
    }

    def updateSchema(c: UpdateSchema) = state match {
      case Initial                      => Left(SchemaDoesNotExist)
      case s: Current if s.rev != c.rev => Left(IncorrectRevisionProvided)
      case s: Current if s.deprecated   => Left(SchemaIsDeprecated)
      case s: Current if s.published    => Left(CannotUpdatePublished)
      case s: Current                   => Right(SchemaUpdated(s.id, s.rev + 1, c.meta, c.value))
    }

    def publishSchema(c: PublishSchema) = state match {
      case Initial                      => Left(SchemaDoesNotExist)
      case s: Current if s.rev != c.rev => Left(IncorrectRevisionProvided)
      case s: Current if s.deprecated   => Left(SchemaIsDeprecated)
      case s: Current if s.published    => Left(CannotUpdatePublished)
      case s: Current                   => Right(SchemaPublished(s.id, s.rev + 1, c.meta))
    }

    def deprecateSchema(c: DeprecateSchema) = state match {
      case Initial                      => Left(SchemaDoesNotExist)
      case s: Current if s.rev != c.rev => Left(IncorrectRevisionProvided)
      case s: Current if s.deprecated   => Left(SchemaIsDeprecated)
      case s: Current                   => Right(SchemaDeprecated(s.id, s.rev + 1, c.meta))
    }

    cmd match {
      case c: CreateSchema    => createSchema(c)
      case c: UpdateSchema    => updateSchema(c)
      case c: PublishSchema   => publishSchema(c)
      case c: DeprecateSchema => deprecateSchema(c)
    }
  }
}

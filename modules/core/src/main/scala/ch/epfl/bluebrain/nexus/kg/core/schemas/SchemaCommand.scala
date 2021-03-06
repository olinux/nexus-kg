package ch.epfl.bluebrain.nexus.kg.core.schemas

import ch.epfl.bluebrain.nexus.commons.iam.acls.Meta
import io.circe.Json

/**
  * Enumeration type for commands that apply to schemas.
  */
sealed trait SchemaCommand extends Product with Serializable {

  /**
    * @return the unique identifier for the schema for which this command will be evaluated
    */
  def id: SchemaId

  /**
    * @return the metadata associated to this command
    */
  def meta: Meta
}

object SchemaCommand {

  /**
    * Command that signals the intent to create a new schema.
    *
    * @param id    the unique identifier for the schema to be created
    * @param meta  the metadata associated to this command
    * @param value the json representation of the schema
    */
  final case class CreateSchema(id: SchemaId, meta: Meta, value: Json) extends SchemaCommand

  /**
    * Command that signals the intent to update a schema value.
    *
    * @param id    the unique identifier for the schema to be updated
    * @param rev   the last known revision of the schema
    * @param meta  the metadata associated to this command
    * @param value the new json representation of the schema
    */
  final case class UpdateSchema(id: SchemaId, rev: Long, meta: Meta, value: Json) extends SchemaCommand

  /**
    * Command that signals the intent to publish a schema.
    *
    * @param id   the unique identifier for the schema to be published
    * @param rev  the last known revision of the schema
    * @param meta the metadata associated to this command
    */
  final case class PublishSchema(id: SchemaId, rev: Long, meta: Meta) extends SchemaCommand

  /**
    * Command that signals the intent to deprecate a schema.
    *
    * @param id   the unique identifier for the schema to be deprecated
    * @param rev  the last known revision of the schema
    * @param meta the metadata associated to this command
    */
  final case class DeprecateSchema(id: SchemaId, rev: Long, meta: Meta) extends SchemaCommand

}

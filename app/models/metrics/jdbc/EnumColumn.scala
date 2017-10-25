package models.metrics.jdbc

import anorm._
import scala.util.Try

object EnumColumn {

  def forEnum[E <: Enumeration](enum: E): Column[E#Value] = {
    Column.nonNull { (value, meta) =>
      val MetaDataItem(qualified, nullable, clazz) = meta
      value match {
        case d: E#Value => Right(d)
        case s: String => Try { enum.withName(s) } match {
          case scala.util.Success(v) => Right(v)
          case scala.util.Failure(ex) => Left(TypeDoesNotMatch(
            s"Cannot convert $value: ${value.asInstanceOf[AnyRef].getClass} to ${enum.getClass} for column $qualified"))
        }
        case _ => Left(TypeDoesNotMatch(s"Cannot convert $value: ${value.asInstanceOf[AnyRef].getClass} to ${enum.getClass} for column $qualified"))
      }
    }
  }
}
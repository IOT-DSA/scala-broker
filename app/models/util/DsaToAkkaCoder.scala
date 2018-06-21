package models.util

import java.net.URLEncoder
import java.net.URLDecoder

/**
  * For converting DSA paths to Actors paths.
  * Each time we get path from DSA request and use it in Akka's functions - use #forAkka
  * and use #forDsa in cases when we create DSA's path based on Actor's path.
  */
object DsaToAkkaCoder {

  val PATH_SEP = "/"

  def dsaToAkkaPath(path: String, charset: String = "UTF-8") =
    processParts(path, PATH_SEP, URLEncoder.encode(_, charset))


  def akkaToDsaPath(path: String, charset: String = "UTF-8") =
    processParts(path, PATH_SEP, URLDecoder.decode(_, charset))


  def processParts(path: String, separator: String, proc:(String) => String) =
    path.split(separator).map(proc(_)).mkString(separator)

  implicit class DsaToAkkaCoderFuncs(val s: String) {
    def forAkka = dsaToAkkaPath(s)
    def forDsa = akkaToDsaPath(s)
  }
}

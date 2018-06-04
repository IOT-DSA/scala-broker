package models.handshake

class Salt {

  var base: String
  val inc: String

  var content: String



  def generate(typ: Integer) =
  {
      _saltInc[typ] += DSRandom.instance.nextUint16();
    //  salts[type] = '${_saltBases[type]}${_saltInc[type].toRadixString(16)}';
  }
}

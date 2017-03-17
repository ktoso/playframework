package play.core.server

class RequestIdsGen {
  private var id = 0L

  def next() = {
    id += 1
    id
  }

}

package play.core.server.akkahttp;

import akka.annotation.InternalApi;
import akka.stream.Materializer;
import akka.stream.impl.fusing.GraphInterpreter;
import akka.stream.impl.fusing.GraphInterpreter$;

/** INTERNAL API
 * Access to private[akka] sub fusing materializer in current GraphInterpreter.
 * 
 * The current interpreter is stored in a ThreadLocal which allows us to get 
 * access to it even from normal functions - which we use to subFuse Accumulators
 * into the main stream of the Akka HTTP server.
 * 
 * This could be done cleaner, by making all those GraphStages.
 */
@InternalApi
final public class InternalSubFusingMaterializerAccess {
  public static Materializer currentInterpreterSubFusingMaterializer() {
    return GraphInterpreter$.MODULE$.currentInterpreter().subFusingMaterializer();
  } 
}

/**
 * Copyright 2016 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.finagle;

import com.twitter.finagle.stats.InMemoryStatsReceiver;
import com.twitter.finagle.tracing.Annotation;
import com.twitter.finagle.tracing.Record;
import com.twitter.finagle.tracing.TraceId;
import com.twitter.util.Duration;
import com.twitter.util.MockTimer;
import com.twitter.util.Time;
import java.net.InetSocketAddress;
import java.util.Date;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import zipkin.Span;
import zipkin.storage.InMemoryStorage;

import static com.twitter.util.Time.fromMilliseconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static scala.Option.empty;
import static scala.collection.JavaConversions.mapAsJavaMap;
import static zipkin.finagle.FinagleTestObjects.TODAY;
import static zipkin.finagle.FinagleTestObjects.child;
import static zipkin.finagle.FinagleTestObjects.root;
import static zipkin.finagle.FinagleTestObjects.seq;

public class SpanRecorderTest {
  @Rule
  public WithTimeAt time = new WithTimeAt(TODAY);
  MockTimer timer = new MockTimer();

  InMemoryStatsReceiver stats = new InMemoryStatsReceiver();
  InMemoryStorage mem = new InMemoryStorage();
  SpanRecorder recorder;

  @Before
  public void setRecorder() {
    // Recorder schedules a flusher thread on instantiation. Do this in a Before block so
    // that we can control time.
    recorder = new SpanRecorder(mem.asyncSpanConsumer(), stats, timer);
  }

  /** This is replaying actual events that happened with Finagle's tracer */
  @Test public void examplerootAndChild() {

    // Initiating a server-span based on an incoming request
    advanceAndRecord(0, root, new Annotation.Rpc("GET"));
    advanceAndRecord(4, root, new Annotation.BinaryAnnotation("http.uri", "/"));
    advanceAndRecord(15, root, new Annotation.ServiceName("frontend"));
    advanceAndRecord(0, root, new Annotation.BinaryAnnotation("srv/finagle.version", "6.36.0"));
    advanceAndRecord(0, root, new Annotation.ServerRecv());
    advanceAndRecord(1, root, new Annotation.LocalAddr(socketAddr("127.0.0.1", 8081)));
    advanceAndRecord(1, root, new Annotation.ServerAddr(socketAddr("127.0.0.1", 8081)));
    advanceAndRecord(1, root, new Annotation.ClientAddr(socketAddr("127.0.0.1", 58624)));

    // Creating a new child-span based on an outgoing request
    advanceAndRecord(3, child, new Annotation.Rpc("GET"));
    advanceAndRecord(0, child, new Annotation.BinaryAnnotation("http.uri", "/api"));
    advanceAndRecord(0, child, new Annotation.ServiceName("frontend"));
    advanceAndRecord(0, child, new Annotation.BinaryAnnotation("clnt/finagle.version", "6.36.0"));
    advanceAndRecord(0, child, new Annotation.ClientSend());
    advanceAndRecord(46, child, Annotation.WireSend$.MODULE$);
    advanceAndRecord(7, child, new Annotation.ServerAddr(socketAddr("127.0.0.1", 9000)));
    advanceAndRecord(1, child, new Annotation.ClientAddr(socketAddr("127.0.0.1", 58627)));
    advanceAndRecord(178, child, Annotation.WireRecv$.MODULE$);
    advanceAndRecord(2, child, new Annotation.ClientRecv());

    // Finishing the server span
    advanceAndRecord(40, root, new Annotation.ServerSend());

    List<Span> trace = mem.spanStore().getTrace(root.traceId().toLong());
    assertThat(trace.get(0).annotations).extracting(a -> a.value).containsExactly(
        "sr", "ss"
    );
    assertThat(trace.get(1).annotations).extracting(a -> a.value).containsExactly(
        "cs", "ws", "wr", "cr"
    );
  }

  private InetSocketAddress socketAddr(String host, int port) {
    return new InetSocketAddress(host, port);
  }

  @Test public void incrementsCounterWhenUnexpected_binaryAnnotation() throws Exception {
    recorder.record(
        new Record(root, fromMilliseconds(TODAY),
            new Annotation.BinaryAnnotation("web", new Date()), empty())
    );

    assertThat(mapAsJavaMap(stats.counters())).containsExactly(
        entry(seq("record", "unhandled", "java.util.Date"), 1)
    );
  }

  @Test public void incrementsCounterWhenUnexpected_annotation() throws Exception {
    recorder.record(
        new Record(root, fromMilliseconds(TODAY), new FancyAnnotation(), empty())
    );

    assertThat(mapAsJavaMap(stats.counters())).containsExactly(
        entry(seq("record", "unhandled", FancyAnnotation.class.getName()), 1)
    );
  }

  @Test public void reportsSpanOn_ClientRecv() throws Exception {
    advanceAndRecord(0, root, new Annotation.ClientSend());
    advanceAndRecord(1, root, new Annotation.ClientRecv());

    Span span = mem.spanStore().getTrace(root.traceId().toLong()).get(0);
    assertThat(span.annotations).extracting(a -> a.value).containsExactly(
        "cs", "cr"
    );
  }

  @Test public void reportsSpanOn_Timeout() throws Exception {
    advanceAndRecord(0, root, new Annotation.ClientSend());
    advanceAndRecord(1, root, new Annotation.Message("finagle.timeout"));

    Span span = mem.spanStore().getTrace(root.traceId().toLong()).get(0);
    assertThat(span.annotations).extracting(a -> a.value).containsExactly(
        "cs", "finagle.timeout"
    );
  }

  @Test public void reportsSpanOn_ServerSend() throws Exception {
    advanceAndRecord(0, root, new Annotation.ServerRecv());
    advanceAndRecord(1, root, new Annotation.ServerSend());

    Span span = mem.spanStore().getTrace(root.traceId().toLong()).get(0);
    assertThat(span.annotations).extracting(a -> a.value).containsExactly(
        "sr", "ss"
    );
  }

  /** ServiceName can be set late, but it should be consistent across annotations. */
  @Test public void serviceNameAppliesRetroactively() throws Exception {
    advanceAndRecord(0, root, new Annotation.Rpc("GET"));
    advanceAndRecord(0, root, new Annotation.ServerRecv());
    advanceAndRecord(0, root, new Annotation.ServiceName("frontend"));
    advanceAndRecord(15, root, new Annotation.ServerSend());

    Span span = mem.spanStore().getTrace(root.traceId().toLong()).get(0);
    assertThat(span.annotations).extracting(a -> a.endpoint.serviceName).containsExactly(
        "frontend", "frontend"
    );
  }

  @Test public void flushesIncompleteSpans() throws Exception {
    advanceAndRecord(0, root, new Annotation.Rpc("GET"));
    advanceAndRecord(15, root, new Annotation.ServiceName("frontend"));
    advanceAndRecord(0, root, new Annotation.ServerRecv());
    // Note: there's no ServerSend() which would complete the span.

    time.advance(recorder.ttl.plus(Duration.fromMilliseconds(1))); // advance timer
    timer.tick(); // invokes a flush

    Span span = mem.spanStore().getTrace(root.traceId().toLong()).get(0);
    assertThat(span.idString()).isEqualTo(root.toString());
    assertThat(span.name).isEqualTo("get");
    assertThat(span.annotations).extracting(a -> a.value).containsExactly(
        "sr", "finagle.flush"
    );
  }

  private void advanceAndRecord(int millis, TraceId traceId, Annotation annotation) {
    time.advance(Duration.fromMilliseconds(millis));
    recorder.record(new Record(traceId, Time.now(), annotation, empty()));
  }

  /** Better to drop instead of crash on expected new Annotation types */
  class FancyAnnotation implements Annotation {

  }
}


package com.shawnma;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventIterator;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.MethodEntryRequest;
import com.sun.jdi.request.MethodExitRequest;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * This class processes incoming JDI events and displays them
 *
 * @author Robert Field
 */
public class EventThread extends Thread {

  private final VirtualMachine vm;   // Running VM
  private final String[] includes;   // Packages to exclude
  private final PrintWriter writer;  // Where output goes
  private final int endLine;

  private boolean connected = true;  // Connected to VM
  private boolean vmDied = true;     // VMDeath occurred

  // Maps ThreadReference to ThreadTrace instances
  private Map<ThreadReference, ThreadTrace> traceMap =
      new HashMap<>();

  public EventThread(VirtualMachine vm, String[] includes, String classToTrack, int startLine,
      int endLine, PrintWriter writer) {
    this.vm = vm;
    this.writer = writer;
    this.includes = includes;
    this.endLine = endLine;
    EventRequestManager mgr = vm.eventRequestManager();
    List<ReferenceType> classes = vm.classesByName(classToTrack);
    ReferenceType refType = classes.get(0);
    try {
      Location loc = refType.locationsOfLine(startLine).get(0);
      mgr.createBreakpointRequest(loc).enable();
      loc = refType.locationsOfLine(endLine).get(0);
      mgr.createBreakpointRequest(loc).enable();
    } catch (AbsentInformationException e) {
      e.printStackTrace();
    }
  }

  /**
   * Run the event handling thread.
   * As long as we are connected, get event sets off
   * the queue and dispatch the events within them.
   */
  @Override
  public void run() {
    EventQueue queue = vm.eventQueue();
    while (connected) {
      try {
        EventSet eventSet = queue.remove();
        EventIterator it = eventSet.eventIterator();
        while (it.hasNext()) {
          handleEvent(it.nextEvent());
        }
        eventSet.resume();
      } catch (InterruptedException exc) {
        // Ignore
      } catch (VMDisconnectedException discExc) {
        handleDisconnectedException();
        break;
      }
    }
  }

  /**
   * Create the desired event requests, and enable
   * them so that we will get events.
   *
   * @param watchFields Do we want to watch assignments to fields
   */
  void setEventRequests(boolean watchFields) {
//    EventRequestManager mgr = vm.eventRequestManager();
//    List<ReferenceType> classes = vm.classesByName(
//        "com.google.wireless.android.finsky.devicefe.billing.purchase.PreparePurchase");
//    ReferenceType refType = classes.get(0);
//    try {
//      Location loc = refType.locationsOfLine(553).get(0);
//      mgr.createBreakpointRequest(loc).enable();
//    } catch (AbsentInformationException e) {
//      e.printStackTrace();
//    }
//        // want all exceptions
//        ExceptionRequest excReq = mgr.createExceptionRequest(null,
//                                                             true, true);
//        // suspend so we can step
//        excReq.setSuspendPolicy(EventRequest.SUSPEND_ALL);
//        excReq.enable();
//    ClassPrepareRequest cpr = mgr.createClassPrepareRequest();
//    cpr.addClassFilter("com.google.wireless.android.finsky.devicefe.billing.purchase.PreparePurchase");
//    cpr.enable();

//
//    ThreadDeathRequest tdr = mgr.createThreadDeathRequest();
//    // Make sure we sync on thread death
//    tdr.setSuspendPolicy(EventRequest.SUSPEND_ALL);
//    tdr.enable();
//
//    if (watchFields) {
//      ClassPrepareRequest cpr = mgr.createClassPrepareRequest();
//      for (int i = 0; i < excludes.length; ++i) {
//        cpr.addClassExclusionFilter(excludes[i]);
//      }
//      cpr.setSuspendPolicy(EventRequest.SUSPEND_ALL);
//      cpr.enable();
//    }
  }

  /**
   * This class keeps context on events in one thread.
   * In this implementation, context is the indentation prefix.
   */
  class ThreadTrace {

    final ThreadReference thread;
    private MethodExitRequest methodExitRequest;

    private MethodEntryRequest methodEntryRequest;
    private StringBuilder indent;
    private List<String> entries = new ArrayList<>();
    private boolean recording = true;

    ThreadTrace(ThreadReference thread) {
      this.thread = thread;
      indent = new StringBuilder();
    }

    void handleBreakpoint(BreakpointEvent event) {
      if (event.location().lineNumber() == endLine) {
        methodEntryRequest.disable();
        methodExitRequest.disable();
        synchronized (writer) {
          if (entries.isEmpty()) {
            return;
          }
          System.out.println("dumping call history");
          writer.println("=----===== " + thread.name() + " ====----==");
          entries.forEach(writer::println);
          writer.flush();
          entries.clear();
        }
      } else {
        EventRequestManager mgr = vm.eventRequestManager();

        methodEntryRequest = mgr.createMethodEntryRequest();
        Stream.of(includes).forEach(methodEntryRequest::addClassFilter);
        methodEntryRequest.setSuspendPolicy(EventRequest.SUSPEND_NONE);
        methodEntryRequest.addThreadFilter(thread);
        methodEntryRequest.enable();

        methodExitRequest = mgr.createMethodExitRequest();
        Stream.of(includes).forEach(methodExitRequest::addClassFilter);
        methodExitRequest.setSuspendPolicy(EventRequest.SUSPEND_NONE);
        methodExitRequest.addThreadFilter(thread);
        methodExitRequest.enable();
      }
      vm.resume();
    }

    void methodEntryEvent(MethodEntryEvent event) {
      String method = event.method().declaringType().name() + "." + event.method().name();
      if (recording) {
        entries.add(indent + method);
        indent.append("  ");
      }
    }

    void methodExitEvent(MethodExitEvent event) {
      if (recording) {
        if (indent.length() > 0) {
          indent.setLength(indent.length() - 2);
        }
      }
    }
//
//    void fieldWatchEvent(ModificationWatchpointEvent event) {
//      Field field = event.field();
//      Value value = event.valueToBe();
//      println("    " + field.name() + " = " + value);
//    }
//
//    void exceptionEvent(ExceptionEvent event) {
//      println("Exception: " + event.exception() +
//          " catch: " + event.catchLocation());
//
//      // Step to the catch
//      EventRequestManager mgr = vm.eventRequestManager();
//      StepRequest req = mgr.createStepRequest(thread,
//          StepRequest.STEP_MIN,
//          StepRequest.STEP_INTO);
//      req.addCountFilter(1);  // next step only
//      req.setSuspendPolicy(EventRequest.SUSPEND_ALL);
//      req.enable();
//    }
//
//    // Step to exception catch
//    void stepEvent(StepEvent event) {
//      // Adjust call depth
//      int cnt = 0;
//      indent = new StringBuilder("");
//      try {
//        cnt = thread.frameCount();
//      } catch (IncompatibleThreadStateException exc) {
//      }
//      while (cnt-- > 0) {
//        indent.append("| ");
//      }
//
//      EventRequestManager mgr = vm.eventRequestManager();
//      mgr.deleteEventRequest(event.request());
//    }
//
//    void threadDeathEvent(ThreadDeathEvent event) {
//      indent = new StringBuilder("");
//      println("====== " + thread.name() + " end ======");
//    }
  }

  /**
   * Returns the ThreadTrace instance for the specified thread,
   * creating one if needed.
   */
  ThreadTrace threadTrace(ThreadReference thread) {
    ThreadTrace trace = traceMap.get(thread);
    if (trace == null) {
      trace = new ThreadTrace(thread);
      traceMap.put(thread, trace);
    }
    return trace;
  }

  /**
   * Dispatch incoming events
   */
  private void handleEvent(Event event) {
//    if (event instanceof ExceptionEvent) {
//      exceptionEvent((ExceptionEvent) event);
//    } else if (event instanceof ModificationWatchpointEvent) {
//      fieldWatchEvent((ModificationWatchpointEvent) event);
    if (event instanceof MethodEntryEvent) {
      methodEntryEvent((MethodEntryEvent) event);
    } else if (event instanceof MethodExitEvent) {
      methodExitEvent((MethodExitEvent) event);
//    } else if (event instanceof StepEvent) {
//      stepEvent((StepEvent) event);
//    } else if (event instanceof ThreadDeathEvent) {
//      threadDeathEvent((ThreadDeathEvent) event);
//    } else if (event instanceof ClassPrepareEvent) {
//      classPrepareEvent((ClassPrepareEvent) event);
//    } else if (event instanceof VMStartEvent) {
//      vmStartEvent((VMStartEvent) event);
    } else if (event instanceof VMDeathEvent) {
      vmDeathEvent((VMDeathEvent) event);
    } else if (event instanceof VMDisconnectEvent) {
      vmDisconnectEvent((VMDisconnectEvent) event);
    } else if (event instanceof BreakpointEvent) {
      threadTrace(((BreakpointEvent) event).thread()).handleBreakpoint(((BreakpointEvent) event));
    }
  }

  /***
   * A VMDisconnectedException has happened while dealing with
   * another event. We need to flush the event queue, dealing only
   * with exit events (VMDeath, VMDisconnect) so that we terminate
   * correctly.
   */
  synchronized void handleDisconnectedException() {
    EventQueue queue = vm.eventQueue();
    while (connected) {
      try {
        EventSet eventSet = queue.remove();
        EventIterator iter = eventSet.eventIterator();
        while (iter.hasNext()) {
          Event event = iter.nextEvent();
          if (event instanceof VMDeathEvent) {
            vmDeathEvent((VMDeathEvent) event);
          } else if (event instanceof VMDisconnectEvent) {
            vmDisconnectEvent((VMDisconnectEvent) event);
          }
        }
        eventSet.resume(); // Resume the VM
      } catch (InterruptedException exc) {
        // ignore
      }
    }
  }

  // Forward event for thread specific processing
  private void methodEntryEvent(MethodEntryEvent event) {
    threadTrace(event.thread()).methodEntryEvent(event);
  }

  // Forward event for thread specific processing
  private void methodExitEvent(MethodExitEvent event) {
    threadTrace(event.thread()).methodExitEvent(event);
  }
//
//  // Forward event for thread specific processing
//  private void stepEvent(StepEvent event) {
//    threadTrace(event.thread()).stepEvent(event);
//  }
//
//  // Forward event for thread specific processing
//  private void fieldWatchEvent(ModificationWatchpointEvent event) {
//    threadTrace(event.thread()).fieldWatchEvent(event);
//  }
//
//  void threadDeathEvent(ThreadDeathEvent event) {
//    ThreadTrace trace = traceMap.get(event.thread());
//    if (trace != null) {  // only want threads we care about
//      trace.threadDeathEvent(event);   // Forward event
//    }
//  }

  /**
   * A new class has been loaded.
   * Set watchpoints on each of its fields
   */
  private void classPrepareEvent(ClassPrepareEvent event) {
//    EventRequestManager mgr = vm.eventRequestManager();
//    ReferenceType refType = event.referenceType();
//    try {
//      Location loc = refType.locationsOfLine(553).get(0);
//      mgr.createBreakpointRequest(loc).enable();
//    } catch (AbsentInformationException e) {
//      e.printStackTrace();
//    }
//    List<Field> fields = event.referenceType().visibleFields();
//    for (Field field : fields) {
//      ModificationWatchpointRequest req =
//          mgr.createModificationWatchpointRequest(field);
//      for (int i = 0; i < excludes.length; ++i) {
//        req.addClassExclusionFilter(excludes[i]);
//      }
//      req.setSuspendPolicy(EventRequest.SUSPEND_NONE);
//      req.enable();
//    }
  }
//
//  private void exceptionEvent(ExceptionEvent event) {
//    ThreadTrace trace = traceMap.get(event.thread());
//    if (trace != null) {  // only want threads we care about
//      trace.exceptionEvent(event);      // Forward event
//    }
//  }

  public void vmDeathEvent(VMDeathEvent event) {
    vmDied = true;
    writer.println("-- The application exited --");
  }

  public void vmDisconnectEvent(VMDisconnectEvent event) {
    connected = false;
    if (!vmDied) {
      writer.println("-- The application has been disconnected --");
    }
  }
}

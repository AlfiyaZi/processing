/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  Part of the Processing project - http://processing.org

  Copyright (c) 2004-13 Ben Fry and Casey Reas
  Copyright (c) 2001-04 Massachusetts Institute of Technology

  This program is free software; you can redistribute it and/or modify
  it under the terms of the GNU General Public License version 2
  as published by the Free Software Foundation.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software Foundation,
  Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package processing.mode.java.runner;

import processing.app.*;
import processing.app.exec.StreamRedirectThread;
import processing.core.*;
import processing.mode.java.JavaBuild;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.io.*;
import java.util.*;

import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;


/**
 * Runs a compiled sketch. As of release 0136, all sketches are run externally
 * to the environment so that a debugging interface can be used. This opens up
 * future options for a decent debugger, but in the meantime fixes several
 * problems with output and error streams, messages getting lost on Mac OS X,
 * the run/stop buttons not working, libraries not shutting down, exceptions
 * not coming through, exceptions being printed twice, having to force quit
 * if you make a bad while() loop, and so on.
 */
public class Runner implements MessageConsumer {
//  private boolean presenting;

  // Object that listens for error messages or exceptions.
  protected RunnerListener listener;

  // Running remote VM
  protected VirtualMachine vm;

  // Thread transferring remote error stream to our error stream
  protected Thread errThread = null;

  // Thread transferring remote output stream to our output stream
  protected Thread outThread = null;

  protected SketchException exception;
  protected Editor editor;
  protected JavaBuild build;
  protected Process process;

  protected PrintStream sketchErr;
  protected PrintStream sketchOut;
  

  public Runner(JavaBuild build, RunnerListener listener) throws SketchException {
    this.listener = listener;
//    this.sketch = sketch;
    this.build = build;

    if (listener instanceof Editor) {
      this.editor = (Editor) listener;
      sketchErr = editor.getConsole().getErr();
      sketchOut = editor.getConsole().getOut();
    } else {
      sketchErr = System.err;
      sketchOut = System.out;
    }

    // Make sure all the imported libraries will actually run with this setup.
    int bits = Base.getNativeBits();
    for (Library library : build.getImportedLibraries()) {
      if (!library.supportsArch(PApplet.platform, bits)) {
        sketchErr.println(library.getName() + " does not run in " + bits + "-bit mode.");
        int opposite = (bits == 32) ? 64 : 32;
        if (Base.isMacOS()) {
          //if (library.supportsArch(PConstants.MACOSX, opposite)) {  // should always be true
          throw new SketchException("To use " + library.getName() + ", " +  
                                    "switch to " + opposite + "-bit mode in Preferences.");
          //}
        } else {
          throw new SketchException(library.getName() + " is only compatible " +  
                                    "with the  " + opposite + "-bit download of Processing.");
          //throw new SketchException(library.getName() + " does not run in " + bits + "-bit mode.");
          // "To use this library, switch to 32-bit mode in Preferences." (OS X)
          //  "To use this library, you must use the 32-bit version of Processing."
        }
      }
    }
  }


  public void launch(boolean presenting) {
    if (launchVirtualMachine(presenting)) {
      generateTrace();
    }
  }
  
  
  public boolean launchVirtualMachine(boolean presenting) {
    String[] vmParams = getMachineParams();
    String[] sketchParams = getSketchParams(presenting);
    int port = 8000 + (int) (Math.random() * 1000);
    String portStr = String.valueOf(port);

    // Older (Java 1.5 and earlier) version, go figure
//    String jdwpArg = "-Xrunjdwp:transport=dt_socket,address=" + portStr + ",server=y,suspend=y";
//    String debugArg = "-Xdebug"; 
    // Newer (Java 1.5+) version that uses JVMTI
    String jdwpArg = "-agentlib:jdwp=transport=dt_socket,address=" + portStr + ",server=y,suspend=y";

    // Everyone works the same under Java 7 (also on OS X) 
    String[] commandArgs = new String[] { Base.getJavaPath(), jdwpArg };
    
    commandArgs = PApplet.concat(commandArgs, vmParams);
    commandArgs = PApplet.concat(commandArgs, sketchParams);
//  PApplet.println(commandArgs);
//  commandArg.setValue(commandArgs);
    launchJava(commandArgs);
    
    AttachingConnector connector = (AttachingConnector) 
      findConnector("com.sun.jdi.SocketAttach");
    //PApplet.println(connector);  // gets the defaults

    Map arguments = connector.defaultArguments();

//  Connector.Argument addressArg =
//    (Connector.Argument)arguments.get("address");
//  addressArg.setValue(addr);
    Connector.Argument portArg =
      (Connector.Argument)arguments.get("port");
    portArg.setValue(portStr);
    
//    Connector.Argument timeoutArg =
//      (Connector.Argument)arguments.get("timeout");
//    timeoutArg.setValue("10000");

    //PApplet.println(connector);  // prints the current
    //com.sun.tools.jdi.AbstractLauncher al;
    //com.sun.tools.jdi.RawCommandLineLauncher rcll;

    //System.out.println(PApplet.javaVersion);
    // http://java.sun.com/j2se/1.5.0/docs/guide/jpda/conninv.html#sunlaunch
    
    try {
//      boolean available = false;
//      while (!available) {
      while (true) {
        try {
          vm = connector.attach(arguments);
//          vm = connector.attach(arguments);
          if (vm != null) {
//            generateTrace();
//            available = true;
            return true;
          }
        } catch (IOException e) {
//          System.out.println("waiting");
//          e.printStackTrace();
          try {
            Thread.sleep(100);
          } catch (InterruptedException e1) {
            e1.printStackTrace(sketchErr);
          }
        }
      }
//    } catch (IOException exc) {
//      throw new Error("Unable to launch target VM: " + exc);
    } catch (IllegalConnectorArgumentsException exc) {
      throw new Error("Internal error: " + exc);
    }
  }


  protected String[] getMachineParams() {
    ArrayList<String> params = new ArrayList<String>();

    //params.add("-Xint"); // interpreted mode
    //params.add("-Xprof");  // profiler
    //params.add("-Xaprof");  // allocation profiler
    //params.add("-Xrunhprof:cpu=samples");  // old-style profiler

    // TODO change this to use run.args = true, run.args.0, run.args.1, etc.
    // so that spaces can be included in the arg names
    String options = Preferences.get("run.options");
    if (options.length() > 0) {
      String pieces[] = PApplet.split(options, ' ');
      for (int i = 0; i < pieces.length; i++) {
        String p = pieces[i].trim();
        if (p.length() > 0) {
          params.add(p);
        }
      }
    }

//    params.add("-Djava.ext.dirs=nuffing");

    if (Preferences.getBoolean("run.options.memory")) {
      params.add("-Xms" + Preferences.get("run.options.memory.initial") + "m");
      params.add("-Xmx" + Preferences.get("run.options.memory.maximum") + "m");
    }

    if (Base.isMacOS()) {
      params.add("-Xdock:name=" + build.getSketchClassName());
//      params.add("-Dcom.apple.mrj.application.apple.menu.about.name=" +
//                 sketch.getMainClassName());
    }
    // sketch.libraryPath might be ""
    // librariesClassPath will always have sep char prepended
    params.add("-Djava.library.path=" +
               build.getJavaLibraryPath() +
               File.pathSeparator +
               System.getProperty("java.library.path"));

    params.add("-cp");
    params.add(build.getClassPath());
//    params.add(sketch.getClassPath() +
//        File.pathSeparator +
//        Base.librariesClassPath);

    // enable assertions
    // http://dev.processing.org/bugs/show_bug.cgi?id=1188
    params.add("-ea");
    //PApplet.println(PApplet.split(sketch.classPath, ':'));

    String outgoing[] = new String[params.size()];
    params.toArray(outgoing);

//    PApplet.println(outgoing);
//    PApplet.println(PApplet.split(outgoing[0], ":"));
//    PApplet.println();
//    PApplet.println("class path");
//    PApplet.println(PApplet.split(outgoing[2], ":"));

    return outgoing;
    //return (String[]) params.toArray();

//  System.out.println("sketch class path");
//  PApplet.println(PApplet.split(sketch.classPath, ';'));
//  System.out.println();
//  System.out.println("libraries class path");
//  PApplet.println(PApplet.split(Base.librariesClassPath, ';'));
//  System.out.println();
  }


  protected String[] getSketchParams(boolean presenting) {
    ArrayList<String> params = new ArrayList<String>();

    // It's dangerous to add your own main() to your code,
    // but if you've done it, we'll respect your right to hang yourself.
    // http://processing.org/bugs/bugzilla/1446.html
    if (build.getFoundMain()) {
      params.add(build.getSketchClassName());

    } else {
      params.add("processing.core.PApplet");

      // get the stored device index (starts at 0)
      int runDisplay = Preferences.getInteger("run.display");

      // If there was a saved location (this guy has been run more than once)
      // then the location will be set to the last position of the sketch window.
      // This will be passed to the PApplet runner using something like
      // --location=30,20
      // Otherwise, the editor location will be passed, and the applet will
      // figure out where to place itself based on the editor location.
      // --editor-location=150,20
      if (editor != null) {  // if running processing-cmd, don't do placement
        GraphicsDevice editorDevice =
          editor.getGraphicsConfiguration().getDevice();
        GraphicsEnvironment ge =
          GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] devices = ge.getScreenDevices();

        // Make sure the display set in Preferences actually exists
        GraphicsDevice runDevice = editorDevice;
        if (runDisplay >= 0 && runDisplay < devices.length) {
          runDevice = devices[runDisplay];
        } else {
          runDevice = editorDevice;
          for (int i = 0; i < devices.length; i++) {
            if (devices[i] == runDevice) {
              runDisplay = i;
              break;
              // Don't set the pref, might be a temporary thing. Users can
              // open/close Preferences to reset the device themselves.
//              Preferences.setInteger("run.display", runDisplay);
            }
          }
        }

        Point windowLocation = editor.getSketchLocation();
//        if (windowLocation != null) {
//          // could check to make sure the sketch location is on the device
//          // that's specified in Preferences, but that's going to be annoying
//          // if you move a sketch to another window, then it keeps jumping
//          // back to the specified window.
////          Rectangle screenRect =
////            runDevice.getDefaultConfiguration().getBounds();
//        }
        if (windowLocation == null) {
          if (editorDevice == runDevice) {
            // If sketches are to be shown on the same display as the editor,
            // provide the editor location so the sketch's main() can place it.
            Point editorLocation = editor.getLocation();
            params.add(PApplet.ARGS_EDITOR_LOCATION + "=" +
                       editorLocation.x + "," + editorLocation.y);
          } else {
            // The sketch's main() will set a location centered on the new
            // display. It has to happen in main() because the width/height
            // of the sketch are not known here.
//             Set a location centered on the other display
//            Rectangle screenRect =
//              runDevice.getDefaultConfiguration().getBounds();
//            int runX =
//            params.add(PApplet.ARGS_LOCATION + "=" + runX + "," + runY);
          }
        } else {
          params.add(PApplet.ARGS_LOCATION + "=" +
                     windowLocation.x + "," + windowLocation.y);
        }
        params.add(PApplet.ARGS_EXTERNAL);
      }

      params.add(PApplet.ARGS_DISPLAY + "=" + runDisplay);
      

      if (presenting) {
        params.add(PApplet.ARGS_FULL_SCREEN);
//        if (Preferences.getBoolean("run.present.exclusive")) {
//          params.add(PApplet.ARGS_EXCLUSIVE);
//        }
        params.add(PApplet.ARGS_STOP_COLOR + "=" +
                   Preferences.get("run.present.stop.color"));
        params.add(PApplet.ARGS_BGCOLOR + "=" +
                   Preferences.get("run.present.bgcolor"));
      }

      params.add(build.getSketchClassName());
      params.add(PApplet.ARGS_SKETCH_FOLDER + "=" + build.getSketchPath());
      // Adding sketch path in the end coz it's likely to
      // contain spaces and things go wrong on UNIX systems.
    }

//    String outgoing[] = new String[params.size()];
//    params.toArray(outgoing);
//    return outgoing;
    return params.toArray(new String[0]);
  }


  protected void launchJava(final String[] args) {
    new Thread(new Runnable() {
      public void run() {
//        PApplet.println("java starting");
        process = PApplet.exec(args);
        try {
//          PApplet.println("java waiting");
          int result = process.waitFor();
//          PApplet.println("java done waiting");
          if (result != 0) {
            String[] errorStrings = PApplet.loadStrings(process.getErrorStream());
            String[] inputStrings = PApplet.loadStrings(process.getInputStream());
            
//            PApplet.println("launchJava stderr:");
//            PApplet.println(errorStrings);
//            PApplet.println("launchJava stdout:");
            PApplet.printArray(inputStrings);
            
            if (errorStrings != null && errorStrings.length > 1) {
              if (errorStrings[0].indexOf("Invalid maximum heap size") != -1) {
                Base.showWarning("Way Too High",
                                 "Please lower the value for \u201Cmaximum available memory\u201D in the\n" +
                                 "Preferences window. For more information, read Help \u2192 Troubleshooting.", null);
              } else {
                for (String err : errorStrings) {
                  sketchErr.println(err);
                }
                sketchErr.println("Using startup command: " + PApplet.join(args, " "));
              }
            } else {
              //exc.printStackTrace();
              sketchErr.println("Could not run the sketch (Target VM failed to initialize).");
              if (Preferences.getBoolean("run.options.memory")) {
                // Only mention this if they've even altered the memory setup
                sketchErr.println("Make sure that you haven't set the maximum available memory too high.");
              }
              sketchErr.println("For more information, read revisions.txt and Help \u2192 Troubleshooting.");
            }
            // changing this to separate editor and listener [091124]
            //if (editor != null) {
            listener.statusError("Could not run the sketch.");
            //}
//            return null;
          }
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }).start();
  }


  /**
   * Generate the trace.
   * Enable events, start thread to display events,
   * start threads to forward remote error and output streams,
   * resume the remote VM, wait for the final event, and shutdown.
   */
  protected void generateTrace() {
    //vm.setDebugTraceMode(debugTraceMode);
//    vm.setDebugTraceMode(VirtualMachine.TRACE_ALL);
//    vm.setDebugTraceMode(VirtualMachine.TRACE_NONE);  // formerly, seems to have no effect
    
    // Calling this seems to set something internally to make the 
    // Eclipse JDI wake up. Without it, an ObjectCollectedException
    // is thrown on excReq.enable(). No idea why this works, 
    // but at least exception handling has returned. (Suspect that it may 
    // block until all or at least some threads are available, meaning 
    // that the app has launched and we have legit objects to talk to). 
    vm.allThreads();
    // The bug may not have been noticed because the test suite waits for 
    // a thread to be available, and queries it by calling allThreads(). 
    // See org.eclipse.debug.jdi.tests.AbstractJDITest for the example.
    
    EventRequestManager mgr = vm.eventRequestManager();
    // get only the uncaught exceptions
    ExceptionRequest excReq = mgr.createExceptionRequest(null, false, true);
//    System.out.println(excReq);
    // this version reports all exceptions, caught or uncaught
//  ExceptionRequest excReq = mgr.createExceptionRequest(null, true, true);
    // suspend so we can step
    excReq.setSuspendPolicy(EventRequest.SUSPEND_ALL);
//    excReq.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
//    excReq.setSuspendPolicy(EventRequest.SUSPEND_NONE);  // another option?
    excReq.enable();
    
    Thread eventThread = new Thread() {
      public void run() {
        try {
          boolean connected = true;
          while (connected) {
            EventQueue eventQueue = vm.eventQueue();
            // remove() blocks until event(s) available
            EventSet eventSet = eventQueue.remove();  
//            listener.vmEvent(eventSet);

            for (Event event : eventSet) {
//              System.out.println("EventThread.handleEvent -> " + event);
              if (event instanceof VMStartEvent) {
                vm.resume();
              } else if (event instanceof ExceptionEvent) {
//                for (ThreadReference thread : vm.allThreads()) {
//                  System.out.println("thread : " + thread);
////                  thread.suspend();
//                }
                exceptionEvent((ExceptionEvent) event);
              } else if (event instanceof VMDisconnectEvent) {
                connected = false;
              } 
            }
          }
//        } catch (VMDisconnectedException e) {
//          Logger.getLogger(VMEventReader.class.getName()).log(Level.INFO, "VMEventReader quit on VM disconnect");
        } catch (Exception e) {
          System.err.println("crashed in event thread due to " + e.getMessage());
//          Logger.getLogger(VMEventReader.class.getName()).log(Level.SEVERE, "VMEventReader quit", e);
          e.printStackTrace();
        }
      }
    };
    eventThread.start();

    
    errThread = 
      new MessageSiphon(process.getErrorStream(), this).getThread();

    outThread = new StreamRedirectThread("JVM stdout Reader",
                                         process.getInputStream(),
                                         sketchOut);
    errThread.start();
    outThread.start();

    // Shutdown begins when event thread terminates
    try {
      if (eventThread != null) eventThread.join();  // is this the problem?
      
//      System.out.println("in here");
      // Bug #852 tracked to this next line in the code.
      // http://dev.processing.org/bugs/show_bug.cgi?id=852
      errThread.join(); // Make sure output is forwarded
//      System.out.println("and then");
      outThread.join(); // before we exit
//      System.out.println("finished join for errThread and outThread");

      // At this point, disable the run button.
      // This happens when the sketch is exited by hitting ESC,
      // or the user manually closes the sketch window.
      // TODO this should be handled better, should it not?
      if (editor != null) {
        editor.deactivateRun();
      }
    } catch (InterruptedException exc) {
      // we don't interrupt
    }
    //System.out.println("and leaving");
  }


  protected Connector findConnector(String connectorName) {
//    List connectors = 
//      com.sun.jdi.Bootstrap.virtualMachineManager().allConnectors();
    List connectors =
      org.eclipse.jdi.Bootstrap.virtualMachineManager().allConnectors();

//    // debug: code to list available connectors
//    Iterator iter2 = connectors.iterator();
//    while (iter2.hasNext()) {
//      Connector connector = (Connector)iter2.next();
//      System.out.println("connector name is " + connector.name());
//    }

    for (Object c : connectors) {
      Connector connector = (Connector) c;
//      System.out.println(connector.name());
//    }
//    Iterator iter = connectors.iterator();
//    while (iter.hasNext()) {
//      Connector connector = (Connector)iter.next();
      if (connector.name().equals(connectorName)) {
        return connector;
      }
    }
    Base.showError("Compiler Error", 
                   "findConnector() failed to find " + 
                    connectorName + " inside Runner", null);
    return null; // Not reachable
  }


  public void exceptionEvent(ExceptionEvent event) {
    ObjectReference or = event.exception();
    ReferenceType rt = or.referenceType();
    String exceptionName = rt.name();
    //Field messageField = Throwable.class.getField("detailMessage");
    Field messageField = rt.fieldByName("detailMessage");
//    System.out.println("field " + messageField);
    Value messageValue = or.getValue(messageField);
//    System.out.println("mess val " + messageValue);

    //"java.lang.ArrayIndexOutOfBoundsException"
    int last = exceptionName.lastIndexOf('.');
    String message = exceptionName.substring(last + 1);
    if (messageValue != null) {
      String messageStr = messageValue.toString();
      if (messageStr.startsWith("\"")) {
        messageStr = messageStr.substring(1, messageStr.length() - 1);
      }
      message += ": " + messageStr;
    }
//    System.out.println("mess type " + messageValue.type());
    //StringReference messageReference = (StringReference) messageValue.type();

    // First just report the exception and its placement
    reportException(message, or, event.thread());
    // Then try to pretty it up with a better message
    handleCommonErrors(exceptionName, message, listener, sketchErr);
    
    if (editor != null) {
      editor.deactivateRun();
    }
  }


  /**
   * Provide more useful explanations of common error messages, perhaps with 
   * a short message in the status area, and (if necessary) a longer message 
   * in the console.
   * 
   * @param exceptionClass Class name causing the error (with full package name)
   * @param message The message from the exception
   * @param listener The Editor or command line interface that's listening for errors
   * @return true if the error was purtified, false otherwise
   */
  public static boolean handleCommonErrors(final String exceptionClass,
                                           final String message,
                                           final RunnerListener listener,
                                           final PrintStream err) {
    if (exceptionClass.equals("java.lang.OutOfMemoryError")) {
      if (message.contains("exceeds VM budget")) {
        // TODO this is a kludge for Android, since there's no memory preference
        listener.statusError("OutOfMemoryError: This code attempts to use more memory than available.");
        err.println("An OutOfMemoryError means that your code is either using up too much memory");
        err.println("because of a bug (e.g. creating an array that's too large, or unintentionally");
        err.println("loading thousands of images), or simply that it's trying to use more memory");
        err.println("than what is supported by the current device.");
      } else {
        listener.statusError("OutOfMemoryError: You may need to increase the memory setting in Preferences.");
        err.println("An OutOfMemoryError means that your code is either using up too much memory");
        err.println("because of a bug (e.g. creating an array that's too large, or unintentionally");
        err.println("loading thousands of images), or that your sketch may need more memory to run.");
        err.println("If your sketch uses a lot of memory (for instance if it loads a lot of data files)");
        err.println("you can increase the memory available to your sketch using the Preferences window.");
      }
    } else if (exceptionClass.equals("java.lang.UnsatisfiedLinkError")) {
      listener.statusError("A library used by this sketch is not installed properly.");
      err.println("A library relies on native code that's not available.");
      err.println("Or only works properly when the sketch is run as a " + 
        ((Base.getNativeBits() == 32) ? "64-bit " : "32-bit ") + " application.");

    } else if (exceptionClass.equals("java.lang.StackOverflowError")) {
      listener.statusError("StackOverflowError: This sketch is attempting too much recursion.");
      err.println("A StackOverflowError means that you have a bug that's causing a function");
      err.println("to be called recursively (it's calling itself and going in circles),");
      err.println("or you're intentionally calling a recursive function too much,");
      err.println("and your code should be rewritten in a more efficient manner.");

    } else if (exceptionClass.equals("java.lang.UnsupportedClassVersionError")) {
      listener.statusError("UnsupportedClassVersionError: A library is using code compiled with an unsupported version of Java.");
      err.println("This version of Processing only supports libraries and JAR files compiled for Java 1.6 or earlier.");
      err.println("A library used by this sketch was compiled for Java 1.7 or later, ");
      err.println("and needs to be recompiled to be compatible with Java 1.6.");

    } else if (exceptionClass.equals("java.lang.NoSuchMethodError") ||
               exceptionClass.equals("java.lang.NoSuchFieldError")) {
      listener.statusError(exceptionClass.substring(10) + ": " +
                           "You may be using a library that's incompatible " +
                           "with this version of Processing.");
    } else {
      return false;
    }
    return true;
  }


  // TODO: This may be called more than one time per error in the VM,
  // presumably because exceptions might be wrapped inside others,
  // and this will fire for both.
  protected void reportException(String message, ObjectReference or, ThreadReference thread) {
    listener.statusError(findException(message, or, thread));
  }


  /**
   * Move through a list of stack frames, searching for references to code
   * found in the current sketch. Return with a RunnerException that contains
   * the location of the error, or if nothing is found, just return with a
   * RunnerException that wraps the error message itself.
   */
  protected SketchException findException(String message, ObjectReference or, ThreadReference thread) {
    try {
      // use to dump the stack for debugging
//      for (StackFrame frame : thread.frames()) {
//        System.out.println("frame: " + frame);
//      }

      List<StackFrame> frames = thread.frames();
      for (StackFrame frame : frames) {
        try {
          Location location = frame.location();
          String filename = null;
          filename = location.sourceName();
          int lineNumber = location.lineNumber() - 1;
          SketchException rex =
            build.placeException(message, filename, lineNumber);
          if (rex != null) {
            return rex;
          }
        } catch (AbsentInformationException e) {
          // Any of the thread.blah() methods can throw an AbsentInformationEx
          // if that bit of data is missing. If so, just write out the error
          // message to the console.
          //e.printStackTrace();  // not useful
          exception = new SketchException(message);
          exception.hideStackTrace();
          listener.statusError(exception);
        }
      }
    } catch (IncompatibleThreadStateException e) {
      // This shouldn't happen, but if it does, print the exception in case
      // it's something that needs to be debugged separately.
      e.printStackTrace(sketchErr);
    }
    // before giving up, try to extract from the throwable object itself
    // since sometimes exceptions are re-thrown from a different context
    try {
      // assume object reference is Throwable, get stack trace
      Method method = ((ClassType) or.referenceType()).concreteMethodByName("getStackTrace", "()[Ljava/lang/StackTraceElement;");
      ArrayReference result = (ArrayReference) or.invokeMethod(thread, method, new ArrayList<Value>(), ObjectReference.INVOKE_SINGLE_THREADED);
      // iterate through stack frames and pull filename and line number for each
      for (Value val: result.getValues()) {
        ObjectReference ref = (ObjectReference)val;
        method = ((ClassType) ref.referenceType()).concreteMethodByName("getFileName", "()Ljava/lang/String;");
        StringReference strref = (StringReference) ref.invokeMethod(thread, method, new ArrayList<Value>(), ObjectReference.INVOKE_SINGLE_THREADED);
        String filename = strref == null ? "Unknown Source" : strref.value();
        method = ((ClassType) ref.referenceType()).concreteMethodByName("getLineNumber", "()I");
        IntegerValue intval = (IntegerValue) ref.invokeMethod(thread, method, new ArrayList<Value>(), ObjectReference.INVOKE_SINGLE_THREADED);
        int lineNumber = intval.intValue() - 1;
        SketchException rex =
          build.placeException(message, filename, lineNumber);
        if (rex != null) {
          return rex;
        }
      }
//      for (Method m : ((ClassType) or.referenceType()).allMethods()) {
//        System.out.println(m + " | " + m.signature() + " | " + m.genericSignature());
//      }
      // Implemented for 2.0b9, writes a stack trace when there's an internal error inside core.
      method = ((ClassType) or.referenceType()).concreteMethodByName("printStackTrace", "()V");
//      System.err.println("got method " + method);
      or.invokeMethod(thread, method, new ArrayList<Value>(), ObjectReference.INVOKE_SINGLE_THREADED);
      
    } catch (Exception e) {
      e.printStackTrace(sketchErr);
    }
    // Give up, nothing found inside the pile of stack frames
    SketchException rex = new SketchException(message);
    // exception is being created /here/, so stack trace is not useful
    rex.hideStackTrace();
    return rex;
  }


  public void close() {
    // TODO make sure stop() has already been called to exit the sketch

    // TODO actually kill off the vm here
    if (vm != null) {
      try {
        vm.exit(0);

      } catch (com.sun.jdi.VMDisconnectedException vmde) {
        // if the vm has disconnected on its own, ignore message
        //System.out.println("harmless disconnect " + vmde.getMessage());
        // TODO shouldn't need to do this, need to do more cleanup
      }
      vm = null;
    }
  }


  // made synchronized for 0087
  // attempted to remove synchronized for 0136 to fix bug #775 (no luck tho)
  // http://dev.processing.org/bugs/show_bug.cgi?id=775
  synchronized public void message(String s) {
//    System.out.println("M" + s.length() + ":" + s.trim()); // + "MMM" + s.length());

    // this eats the CRLFs on the lines.. oops.. do it later
    //if (s.trim().length() == 0) return;

    // this is PApplet sending a message (via System.out.println)
    // that signals that the applet has been quit.
    if (s.indexOf(PApplet.EXTERNAL_STOP) == 0) {
      //System.out.println("external: quit");
      if (editor != null) {
//        editor.internalCloseRunner();  // [091124]
//        editor.handleStop();  // prior to 0192
        editor.internalCloseRunner();  // 0192
      }
      return;
    }

    // this is the PApplet sending us a message that the applet
    // is being moved to a new window location
    if (s.indexOf(PApplet.EXTERNAL_MOVE) == 0) {
      String nums = s.substring(s.indexOf(' ') + 1).trim();
      int space = nums.indexOf(' ');
      int left = Integer.parseInt(nums.substring(0, space));
      int top = Integer.parseInt(nums.substring(space + 1));
      // this is only fired when connected to an editor
      editor.setSketchLocation(new Point(left, top));
      //System.out.println("external: move to " + left + " " + top);
      return;
    }

    // these are used for debugging, in case there are concerns
    // that some errors aren't coming through properly
//    if (s.length() > 2) {
//      System.err.println(newMessage);
//      System.err.println("message " + s.length() + ":" + s);
//    }

    // always shove out the message, since it might not fall under
    // the same setup as we're expecting
    sketchErr.print(s);
    //System.err.println("[" + s.length() + "] " + s);
    sketchErr.flush();
  }
}

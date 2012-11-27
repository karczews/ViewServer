/*
 * Copyright (C) 2011 The Android Open Source Project Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package com.android.debug.hv;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import android.R.bool;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * <p>
 * This class can be used to enable the use of HierarchyViewer inside an application. HierarchyViewer is an Android SDK tool that
 * can be used to inspect and debug the user interface of running applications. For security reasons, HierarchyViewer does not
 * work on production builds (for instance phones bought in store.) By using this class, you can make HierarchyViewer work on any
 * device. You must be very careful however to only enable HierarchyViewer when debugging your application.
 * </p>
 * <p>
 * To use this view server, your application must require the INTERNET permission.
 * </p>
 * <p>
 * The recommended way to use this API is to register activities when they are created, and to unregister them when they get
 * destroyed:
 * </p>
 * 
 * <pre>
 * 
 * 
 * public class MyActivity extends Activity {
 * 
 *     public void onCreate( Bundle savedInstanceState ) {
 *         super.onCreate(savedInstanceState);
 *         // Set content view, etc.
 *         ViewServer.get(this).addWindow(this);
 *     }
 * 
 *     public void onDestroy() {
 *         super.onDestroy();
 *         ViewServer.get(this).removeWindow(this);
 *     }
 * 
 *     public void onResume() {
 *         super.onResume();
 *         ViewServer.get(this).setFocusedWindow(this);
 *     }
 * }
 * </pre>
 * <p>
 * In a similar fashion, you can use this API with an InputMethodService:
 * </p>
 * 
 * <pre>
 * 
 * 
 * public class MyInputMethodService extends InputMethodService {
 * 
 *     public void onCreate() {
 *         super.onCreate();
 *         View decorView = getWindow().getWindow().getDecorView();
 *         String name = "MyInputMethodService";
 *         ViewServer.get(this).addWindow(decorView, name);
 *     }
 * 
 *     public void onDestroy() {
 *         super.onDestroy();
 *         View decorView = getWindow().getWindow().getDecorView();
 *         ViewServer.get(this).removeWindow(decorView);
 *     }
 * 
 *     public void onStartInput( EditorInfo attribute, boolean restarting ) {
 *         super.onStartInput(attribute, restarting);
 *         View decorView = getWindow().getWindow().getDecorView();
 *         ViewServer.get(this).setFocusedWindow(decorView);
 *     }
 * }
 * </pre>
 */
public class ViewServer implements Runnable {

    /**
     * The default port used to start view servers.
     */
    private static final boolean DEBUG = true;
    private static final String TAG = "ViewServer";
    private static final int VIEW_SERVER_DEFAULT_PORT = 4949;
    private static final int VIEW_SERVER_MAX_CONNECTIONS = 10;
    private static final String BUILD_TYPE_USER = "user";
    // Debug facility
    private static final String LOG_TAG = "ViewServer";
    private static final String VALUE_PROTOCOL_VERSION = "5";
    private static final String VALUE_SERVER_VERSION = "5";
    // Protocol commands
    // Returns the protocol version
    private static final String COMMAND_PROTOCOL_VERSION = "PROTOCOL";
    // Returns the server version
    private static final String COMMAND_SERVER_VERSION = "SERVER";
    // Lists all of the available windows in the system
    private static final String COMMAND_WINDOW_MANAGER_LIST = "LIST";
    // Keeps a connection open and notifies when the list of windows changes
    private static final String COMMAND_WINDOW_MANAGER_AUTOLIST = "AUTOLIST";
    // Returns the focused window
    private static final String COMMAND_WINDOW_MANAGER_GET_FOCUS = "GET_FOCUS";
    private static final String COMMAND_UPDATE_PROPERTY = "UPDATE_PROPERTY";
    private static final String GET_VISIBILITY_KEY = "getVisibility()";
    private ServerSocket mServer;
    private final int mPort;
    private Thread mThread;
    private ExecutorService mThreadPool;
    private final List<WindowListener> mListeners = new CopyOnWriteArrayList<ViewServer.WindowListener>();
    private final HashMap<View, String> mWindows = new HashMap<View, String>();
    /**
     * TEMP::
     */
    private View mRootView;
    private Handler mUiHandler;
    
    private final ReentrantReadWriteLock mWindowsLock = new ReentrantReadWriteLock();
    private View mFocusedWindow;
    private final ReentrantReadWriteLock mFocusLock = new ReentrantReadWriteLock();
    private static ViewServer sServer;

    /**
     * Returns a unique instance of the ViewServer. This method should only be called from the main thread of your application.
     * The server will have the same lifetime as your process. If your application does not have the
     * <code>android:debuggable</code> flag set in its manifest, the server returned by this method will be a dummy object that
     * does not do anything. This allows you to use the same code in debug and release versions of your application.
     * @param context
     * A Context used to check whether the application is debuggable, this can be the application context
     */
    public static ViewServer get( Context context ) {
        ApplicationInfo info = context.getApplicationInfo();
        if (BUILD_TYPE_USER.equals(Build.TYPE) && (info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            if (sServer == null) {
                if (DEBUG)
                    Log.d(TAG, "creating server instance @port:" + ViewServer.VIEW_SERVER_DEFAULT_PORT);
                sServer = new ViewServer(ViewServer.VIEW_SERVER_DEFAULT_PORT);
            }
            if (!sServer.isRunning()) {
                try {
                    sServer.start();
                } catch (IOException e) {
                    Log.d(LOG_TAG, "Error:", e);
                }
            }
        } else {
            sServer = new NoopViewServer();
        }
        if (DEBUG)
            Log.d(TAG, "[>>] get() - " + sServer);
        return sServer;
    }

    private ViewServer() {
        mPort = -1;
    }

    /**
     * Creates a new ViewServer associated with the specified window manager on the specified local port. The server is not
     * started by default.
     * @param port
     * The port for the server to listen to.
     * @see #start()
     */
    private ViewServer(int port) {
        mPort = port;
    }

    /**
     * Starts the server.
     * @return True if the server was successfully created, or false if it already exists.
     * @throws IOException
     * If the server cannot be created.
     * @see #stop()
     * @see #isRunning()
     * @see WindowManagerService#startViewServer(int)
     */
    public boolean start() throws IOException {
        if (mThread != null) {
            return false;
        }
        if (DEBUG)
            Log.d(TAG, "starting server ... port:" + mPort);
        mThread = new Thread(this, "Local View Server [port=" + mPort + "]");
        mThreadPool = Executors.newFixedThreadPool(VIEW_SERVER_MAX_CONNECTIONS);
        mThread.start();
        return true;
    }

    /**
     * Stops the server.
     * @return True if the server was stopped, false if an error occurred or if the server wasn't started.
     * @see #start()
     * @see #isRunning()
     * @see WindowManagerService#stopViewServer()
     */
    public boolean stop() {
        if (DEBUG)
            Log.d(TAG, "stoping server ...");
        if (mThread != null) {
            mThread.interrupt();
            if (mThreadPool != null) {
                try {
                    mThreadPool.shutdownNow();
                } catch (SecurityException e) {
                    Log.w(LOG_TAG, "Could not stop all view server threads");
                }
            }
            mThreadPool = null;
            mThread = null;
            try {
                mServer.close();
                mServer = null;
                return true;
            } catch (IOException e) {
                Log.w(LOG_TAG, "Could not close the view server");
            }
        }
        mWindowsLock.writeLock().lock();
        try {
            mWindows.clear();
        } finally {
            mWindowsLock.writeLock().unlock();
        }
        mFocusLock.writeLock().lock();
        try {
            mFocusedWindow = null;
        } finally {
            mFocusLock.writeLock().unlock();
        }
        return false;
    }

    /**
     * Indicates whether the server is currently running.
     * @return True if the server is running, false otherwise.
     * @see #start()
     * @see #stop()
     * @see WindowManagerService#isViewServerRunning()
     */
    public boolean isRunning() {
        return mThread != null && mThread.isAlive();
    }

    /**
     * Invoke this method to register a new view hierarchy.
     * @param activity
     * The activity whose view hierarchy/window to register
     * @see #addWindow(View, String)
     * @see #removeWindow(Activity)
     */
    public void addWindow(Activity activity) {
        String name = activity.getTitle().toString();
        if (TextUtils.isEmpty(name)) {
            name = activity.getClass().getCanonicalName() + "/0x" + System.identityHashCode(activity);
        } else {
            name += "(" + activity.getClass().getCanonicalName() + ")";
        }
        addWindow(activity.getWindow().getDecorView(), name);
    }

    /**
     * Invoke this method to unregister a view hierarchy.
     * @param activity
     * The activity whose view hierarchy/window to unregister
     * @see #addWindow(Activity)
     * @see #removeWindow(View)
     */
    public void removeWindow( Activity activity ) {
        removeWindow(activity.getWindow().getDecorView());
    }

    /**
     * Invoke this method to register a new view hierarchy.
     * @param view
     * A view that belongs to the view hierarchy/window to register
     * @name name The name of the view hierarchy/window to register
     * @see #removeWindow(View)
     */
    public void addWindow( View view, String name ) {
        mWindowsLock.writeLock().lock();
        mRootView = view;
        //mUiHandler = new Handler(view.getContext().getMainLooper());
        try {
            mWindows.put(view.getRootView(), name);
        } finally {
            mWindowsLock.writeLock().unlock();
        }
        fireWindowsChangedEvent();
    }

    /**
     * Invoke this method to unregister a view hierarchy.
     * @param view
     * A view that belongs to the view hierarchy/window to unregister
     * @see #addWindow(View, String)
     */
    public void removeWindow( View view ) {
        mWindowsLock.writeLock().lock();
        try {
            mWindows.remove(view.getRootView());
        } finally {
            mWindowsLock.writeLock().unlock();
        }
        fireWindowsChangedEvent();
    }

    /**
     * Invoke this method to change the currently focused window.
     * @param activity
     * The activity whose view hierarchy/window hasfocus, or null to remove focus
     */
    public void setFocusedWindow( Activity activity ) {
        setFocusedWindow(activity.getWindow().getDecorView());
    }

    /**
     * Invoke this method to change the currently focused window.
     * @param view
     * A view that belongs to the view hierarchy/window that has focus, or null to remove focus
     */
    public void setFocusedWindow( View view ) {
        mFocusLock.writeLock().lock();
        try {
            mFocusedWindow = view == null ? null : view.getRootView();
        } finally {
            mFocusLock.writeLock().unlock();
        }
        fireFocusChangedEvent();
    }

    /**
     * Main server loop.
     */
    public void run() {
        try {
            mServer = new ServerSocket(mPort, VIEW_SERVER_MAX_CONNECTIONS, InetAddress.getLocalHost());
        } catch (Exception e) {
            Log.w(LOG_TAG, "Starting ServerSocket error: ", e);
        }
        while (mServer != null && Thread.currentThread() == mThread) {
            // Any uncaught exception will crash the system process
            try {
                Socket client = mServer.accept();
                if (DEBUG)
                    Log.d(TAG, "client connected ...");
                if (mThreadPool != null) {
                    mThreadPool.submit(new ViewServerWorker(client));
                } else {
                    try {
                        client.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            } catch (Exception e) {
                Log.w(LOG_TAG, "Connection error: ", e);
            }
        }
    }

    private static boolean writeValue( Socket client, String value ) {
        if (DEBUG)
            Log.d(TAG, "writeValue() val:" + value);
        boolean result;
        BufferedWriter out = null;
        try {
            OutputStream clientStream = client.getOutputStream();
            out = new BufferedWriter(new OutputStreamWriter(clientStream), 8 * 1024);
            out.write(value);
            out.write("\n");
            out.flush();
            result = true;
        } catch (Exception e) {
            result = false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    result = false;
                }
            }
        }
        return result;
    }

    private void fireWindowsChangedEvent() {
        for (WindowListener listener : mListeners) {
            listener.windowsChanged();
        }
    }

    private void fireFocusChangedEvent() {
        for (WindowListener listener : mListeners) {
            listener.focusChanged();
        }
    }

    private void addWindowListener( WindowListener listener ) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    private void removeWindowListener( WindowListener listener ) {
        mListeners.remove(listener);
    }
    private interface WindowListener {

        void windowsChanged();

        void focusChanged();
    }
    private static class UncloseableOuputStream extends OutputStream {

        private final OutputStream mStream;

        UncloseableOuputStream(OutputStream stream) {
            mStream = stream;
        }

        public void close() throws IOException {
            // Don't close the stream
        }

        public boolean equals( Object o ) {
            return mStream.equals(o);
        }

        public void flush() throws IOException {
            mStream.flush();
        }

        public int hashCode() {
            return mStream.hashCode();
        }

        public String toString() {
            return mStream.toString();
        }

        public void write( byte[] buffer, int offset, int count ) throws IOException {
            mStream.write(buffer, offset, count);
        }

        public void write( byte[] buffer ) throws IOException {
            mStream.write(buffer);
        }

        public void write( int oneByte ) throws IOException {
            mStream.write(oneByte);
        }
    }
    private static class NoopViewServer extends ViewServer {

        private NoopViewServer() {
        }

        @Override
        public boolean start() throws IOException {
            return false;
        }

        @Override
        public boolean stop() {
            return false;
        }

        @Override
        public boolean isRunning() {
            return false;
        }

        @Override
        public void addWindow( Activity activity ) {
        }

        @Override
        public void removeWindow( Activity activity ) {
        }

        @Override
        public void addWindow( View view, String name ) {
        }

        @Override
        public void removeWindow( View view ) {
        }

        @Override
        public void setFocusedWindow( Activity activity ) {
        }

        @Override
        public void setFocusedWindow( View view ) {
        }

        @Override
        public void run() {
        }
    }
    private class ViewServerWorker implements Runnable, WindowListener {

        private Socket mClient;
        private boolean mNeedWindowListUpdate;
        private boolean mNeedFocusedWindowUpdate;
        private final Object[] mLock = new Object[0];

        public ViewServerWorker(Socket client) {
            mClient = client;
            mNeedWindowListUpdate = false;
            mNeedFocusedWindowUpdate = false;
        }

        public void run() {
            BufferedReader in = null;
            try {
                in = new BufferedReader(new InputStreamReader(mClient.getInputStream()), 1024);
                final String request = in.readLine();
                Log.d(TAG, "request:  " + request);
                String command;
                String parameters;
                int index = request.indexOf(' ');
                if (index == -1) {
                    command = request;
                    parameters = "";
                } else {
                    command = request.substring(0, index);
                    parameters = request.substring(index + 1);
                }
                if (DEBUG)
                    Log.d(TAG, "executing client command:" + command);
                boolean result;
                if (COMMAND_UPDATE_PROPERTY.equalsIgnoreCase(command)) {
                    result = updateValue(mClient, parameters);
                } else if (COMMAND_PROTOCOL_VERSION.equalsIgnoreCase(command)) {
                    result = writeValue(mClient, VALUE_PROTOCOL_VERSION);
                } else if (COMMAND_SERVER_VERSION.equalsIgnoreCase(command)) {
                    result = writeValue(mClient, VALUE_SERVER_VERSION);
                } else if (COMMAND_WINDOW_MANAGER_LIST.equalsIgnoreCase(command)) {
                    result = listWindows(mClient);
                } else if (COMMAND_WINDOW_MANAGER_GET_FOCUS.equalsIgnoreCase(command)) {
                    result = getFocusedWindow(mClient);
                } else if (COMMAND_WINDOW_MANAGER_AUTOLIST.equalsIgnoreCase(command)) {
                    result = windowManagerAutolistLoop();
                } else {
                    result = windowCommand(mClient, command, parameters);
                }
                if (!result) {
                    Log.w(LOG_TAG, "An error occurred with the command: " + command);
                }
            } catch (IOException e) {
                Log.w(LOG_TAG, "Connection error: ", e);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (mClient != null) {
                    try {
                        mClient.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        private boolean windowCommand( Socket client, String command, String parameters ) {
            if (DEBUG)
                Log.d(TAG, "writeCommand() command:" + command + " params:" + parameters);
            boolean success = true;
            BufferedWriter out = null;
            try {
                // Find the hash code of the window
                int index = parameters.indexOf(' ');
                if (index == -1) {
                    index = parameters.length();
                }
                final String code = parameters.substring(0, index);
                int hashCode = (int) Long.parseLong(code, 16);
                // Extract the command's parameter after the window description
                if (index < parameters.length()) {
                    parameters = parameters.substring(index + 1);
                } else {
                    parameters = "";
                }
                final View window = findWindow(hashCode);
                if (window == null) {
                    return false;
                }
                // call stuff
                final Method dispatch = ViewDebug.class
                        .getDeclaredMethod("dispatchCommand", View.class, String.class, String.class, OutputStream.class);
                dispatch.setAccessible(true);
                dispatch.invoke(null, window, command, parameters, new UncloseableOuputStream(client.getOutputStream()));
                if (!client.isOutputShutdown()) {
                    out = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));
                    out.write("DONE\n");
                    out.flush();
                }
            } catch (Exception e) {
                Log.w(LOG_TAG, "Could not send command " + command + " with parameters " + parameters, e);
                success = false;
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException e) {
                        success = false;
                    }
                }
            }
            return success;
        }

        private View findWindow( int hashCode ) {
            if (hashCode == -1) {
                View window = null;
                mWindowsLock.readLock().lock();
                try {
                    window = mFocusedWindow;
                } finally {
                    mWindowsLock.readLock().unlock();
                }
                return window;
            }
            mWindowsLock.readLock().lock();
            try {
                for (Entry<View, String> entry : mWindows.entrySet()) {
                    if (System.identityHashCode(entry.getKey()) == hashCode) {
                        return entry.getKey();
                    }
                }
            } finally {
                mWindowsLock.readLock().unlock();
            }
            return null;
        }

        private boolean listWindows( Socket client ) {
            boolean result = true;
            BufferedWriter out = null;
            try {
                mWindowsLock.readLock().lock();
                OutputStream clientStream = client.getOutputStream();
                out = new BufferedWriter(new OutputStreamWriter(clientStream), 8 * 1024);
                for (Entry<View, String> entry : mWindows.entrySet()) {
                    out.write(Integer.toHexString(System.identityHashCode(entry.getKey())));
                    out.write(' ');
                    out.append(entry.getValue());
                    out.write('\n');
                }
                out.write("DONE.\n");
                out.flush();
            } catch (Exception e) {
                result = false;
            } finally {
                mWindowsLock.readLock().unlock();
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException e) {
                        result = false;
                    }
                }
            }
            return result;
        }

        private boolean getFocusedWindow( Socket client ) {
            boolean result = true;
            String focusName = null;
            BufferedWriter out = null;
            try {
                OutputStream clientStream = client.getOutputStream();
                out = new BufferedWriter(new OutputStreamWriter(clientStream), 8 * 1024);
                View focusedWindow = null;
                mFocusLock.readLock().lock();
                try {
                    focusedWindow = mFocusedWindow;
                } finally {
                    mFocusLock.readLock().unlock();
                }
                if (focusedWindow != null) {
                    mWindowsLock.readLock().lock();
                    try {
                        focusName = mWindows.get(mFocusedWindow);
                    } finally {
                        mWindowsLock.readLock().unlock();
                    }
                    out.write(Integer.toHexString(System.identityHashCode(focusedWindow)));
                    out.write(' ');
                    out.append(focusName);
                }
                out.write('\n');
                out.flush();
            } catch (Exception e) {
                result = false;
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException e) {
                        result = false;
                    }
                }
            }
            return result;
        }

        public void windowsChanged() {
            synchronized (mLock) {
                mNeedWindowListUpdate = true;
                mLock.notifyAll();
            }
        }

        public void focusChanged() {
            synchronized (mLock) {
                mNeedFocusedWindowUpdate = true;
                mLock.notifyAll();
            }
        }

        private boolean windowManagerAutolistLoop() {
            addWindowListener(this);
            BufferedWriter out = null;
            try {
                out = new BufferedWriter(new OutputStreamWriter(mClient.getOutputStream()));
                while (!Thread.interrupted()) {
                    boolean needWindowListUpdate = false;
                    boolean needFocusedWindowUpdate = false;
                    synchronized (mLock) {
                        while (!mNeedWindowListUpdate && !mNeedFocusedWindowUpdate) {
                            mLock.wait();
                        }
                        if (mNeedWindowListUpdate) {
                            mNeedWindowListUpdate = false;
                            needWindowListUpdate = true;
                        }
                        if (mNeedFocusedWindowUpdate) {
                            mNeedFocusedWindowUpdate = false;
                            needFocusedWindowUpdate = true;
                        }
                    }
                    if (needWindowListUpdate) {
                        out.write("LIST UPDATE\n");
                        out.flush();
                    }
                    if (needFocusedWindowUpdate) {
                        out.write("FOCUS UPDATE\n");
                        out.flush();
                    }
                }
            } catch (Exception e) {
                Log.w(LOG_TAG, "Connection error: ", e);
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException e) {
                        // Ignore
                    }
                }
                removeWindowListener(this);
            }
            return true;
        }

        public boolean updateValue( Socket client, String parameters ) {
            if (DEBUG)
                Log.d(TAG, "updateValue() val:" + parameters);
            boolean result = false;
            int index = parameters.indexOf(' ');
            if (index == -1) {
                index = parameters.length();
            }
            final String code = parameters.substring(0, index);
            int hashCode = (int) Long.parseLong(code, 16);
            // Extract the command's parameter after the window description
            if (index < parameters.length()) {
                parameters = parameters.substring(index + 1);
            } else {
                parameters = "";
            }
            Log.d(TAG, "findcode: hex:" + code + "  int:" + hashCode);
            // final View window = findWindow(hashCode);
            // if (window == null) {
            // Log.e(TAG, "no window found !");
            // writeResponse(client, "NO_VIEW_FOUND\n");
            // return false;
            // }
            final Property property = parseProperty(parameters);
            Log.d(TAG, "parsed prop " + property);
            try {
                final View v = getViewByHashCode(hashCode, (ViewGroup) mRootView);
                Log.d(TAG, "setted value view:" + v + " mRootActivity " + mRootActivity);
                ReflectionUtils.displayMethods(v);
                mUiHandler.post(new Runnable() {

                    @Override
                    public void run() {
                        Log.d(TAG, "setValue " + property.getValue());
                        // ((TextView) v).setText(property.getValue());
                        // v.forceLayout();
                        try {
                            String methodName = parseMethodName(property);
                            Class c = parameterTypeFactory(property);
                            Method method = v.getClass().getMethod(methodName, c);
                            method.invoke(v, property.getValue());
                            v.forceLayout();
                        } catch (Exception e) {
                            Log.d(TAG, "Error when update view", e);
                        }
                    }

                    private Class parameterTypeFactory( Property property ) {
                        return property.getType();
                    }

                    private String parseMethodName( final Property property ) {
                        Log.d(TAG, "parse " + property);
                        String fieldName = property.getName();
                        String methodName = "";
                        if (fieldName.startsWith("text")) {
                            methodName = "set" + fieldName.substring(fieldName.indexOf(":") + 2 /* :m */);
                            return methodName;
                        }
                        if (fieldName.contains(":")){
                            fieldName = fieldName.substring(fieldName.indexOf(":")+1);
                            Log.d(TAG, "cut fieldName " + fieldName);
                        }
                        if (fieldName.startsWith("m")){
                            methodName = "set" + fieldName.substring(fieldName.indexOf("m") + 1);
                        } 
                        if (fieldName.startsWith("get") && fieldName.endsWith("()")) {
                            methodName = "set" + fieldName.substring(fieldName.indexOf("set") + 4, fieldName.length() - 2);
                        } else if (fieldName.startsWith("is") && fieldName.endsWith("()")) {
                            methodName = "set" + fieldName.substring(fieldName.indexOf("is") + 2, fieldName.length() - 2);
                        } 
                        Log.d(TAG, "methodName :" + methodName);
                        return methodName;
                    }
                });
                Log.d(TAG, "setted value view:" + v);
            } catch (Exception e) {
                Log.e(TAG, "error setting value", e);
            }
            result = writeResponse(client, "DONE\n");
            return result;
        }

        private boolean writeResponse( Socket client, String response ) {
            boolean result = false;
            BufferedWriter out = null;
            try {
                OutputStream clientStream = client.getOutputStream();
                out = new BufferedWriter(new OutputStreamWriter(clientStream));// ,
                                                                               // 8
                                                                               // *
                                                                               // 1024);
                out.write(response);
                out.flush();
                result = true;
            } catch (Exception e) {
                Log.e(TAG, "problem while writing response for updateValue", e);
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException e) {
                        Log.e(TAG, "problem while closing output stream", e);
                    }
                }
            }
            return result;
        }
    }

    public static Property parseProperty( String property ) {
        if (DEBUG)
            Log.d(TAG, "property: " + property);
        String name = property.substring(0, property.indexOf("="));
        String value = property.substring(property.indexOf(',') + 1);
        return new Property(name, value);
    }
    private static class Property {

        private final String mName;
        private final String mValue;
        private final boolean mIsMethodProperty;

        Property(String name, String value) {
            mName = name;
            mValue = value;
            if (name != null && name.contains("()")) {
                mIsMethodProperty = true;
            } else {
                mIsMethodProperty = false;
            }
        }

        public Class getType() {
            if (mName.startsWith("text")) {
                return CharSequence.class;
            } else if (mName.startsWith("get")) {
                return int.class;
            } else if (mName.startsWith("is")) {
                return boolean.class;
            }
            return int.class;
        }

        public String getName() {
            return mName;
        }

        public Object getValue() {
            if (GET_VISIBILITY_KEY.equals(mName)) {
                if ("VISIBLE".equals(mValue)) {
                    return 0;
                } else if ("INVISIBLE".equals(mValue)) {
                    return 4;
                } else if ("GONE".equals(mValue)) {
                    return 8;
                }
                try {
                    return Integer.parseInt(mValue);
                } catch (NumberFormatException e) {
                    return 0; //VISIBLE
                }
            }
            if (mName.startsWith("get")) {
                return Integer.parseInt(mValue);
            }
            if (mName.startsWith("is")) {
                return Boolean.parseBoolean(mValue);
            }
            try {
                getType().cast(mValue);
            } catch (ClassCastException e) {
                try {
                    return Integer.parseInt(mValue);
                } catch (NumberFormatException ex) {
                    return mValue;
                }
            }
            return mValue;
        }

        public boolean isMethodProperty() {
            return mIsMethodProperty;
        }

        @Override
        public String toString() {
            return "Property [mName=" + mName + ", mValue=" + mValue + "]";
        }
    }

    public static View getViewByHashCode( int hashCode, ViewGroup vg ) {
        Log.d(TAG, "getViewByHashCode() code:" + hashCode + " group:" + vg);
        View view = null;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            int childHashCode = System.identityHashCode(child);
            Log.d(TAG, "getViewByHashCode() checkHashcode:" + hashCode + "?=" + childHashCode + " ChildHex:"
                    + Integer.toHexString(child.hashCode()));
            if (childHashCode == hashCode)
                return child;
            if (child instanceof ViewGroup) {
                View res = getViewByHashCode(hashCode, (ViewGroup) child);
                if (res != null)
                    return res;
            }
        }
        return view;
    }
}

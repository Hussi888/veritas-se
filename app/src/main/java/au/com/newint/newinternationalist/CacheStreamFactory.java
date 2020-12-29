package au.com.newint.newinternationalist;

import android.os.AsyncTask;
import android.util.Log;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.NullOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * Created by pix on 27/02/15.
 */
public abstract class CacheStreamFactory {
    private final String name;
    protected final CacheStreamFactory fallback;

    //public PreloadTask preloadTask;

    final HashMap<String,PreloadTask> preloadTasks;


    CacheStreamFactory(CacheStreamFactory fallback, String name) {
        Helpers.debugLog("CacheStreamFactory", "creating factory of type " + name + ", fallback is " + ((fallback != null) ? "not" : "") + " null");
        //preloadTask = new PreloadTask();
        this.preloadTasks = new HashMap<>();
        this.fallback = fallback;
        this.name = name;
    }

    public String toString() {
        return "CacheStreamFactory[]";
    }

    interface CachePreloadCallback {
        void onLoad(byte[] payload);
        void onLoadBackground(byte [] payload);
    }

    class PreloadParameters {
        Object lock;
        CachePreloadCallback callback;
        String startingAt;
        String stoppingAt;

        PreloadParameters(Object lock, CachePreloadCallback callback, String startingAt, String stoppingAt) {
            this.lock = lock;
            this.callback = callback;
            this.stoppingAt = stoppingAt;
            this.startingAt = startingAt;
        }
    }

    class PreloadReturn {
        CachePreloadCallback callback;
        byte[] payload;

        PreloadReturn(CachePreloadCallback callback, byte[] payload) {
            this.callback = callback;
            this.payload = payload;
        }
    }

    class PreloadTask extends AsyncTask<PreloadParameters,Integer,PreloadReturn> {

        final ArrayList<CachePreloadCallback> callbacks;

        boolean callbacksProcessed;

        PreloadTask() {
            super();
            callbacksProcessed = false;
            callbacks = new ArrayList<>();
        }

        @Override
        protected PreloadReturn doInBackground(PreloadParameters... params) {

            //CachePreloadCallback callback = null;
            String startingAt = null;
            String stoppingAt = null;
            Object lock;
            if (params != null && params.length > 0) {
                lock = params[0].lock;
            } else {
                Log.e("CacheStreamFactory","params.length <= 0");
                return new PreloadReturn(null,null);
            }
            // TODO: should this also be wrapped in params != null && params.length > 0
            synchronized (lock) {
                //callback = params[0].callback;
                startingAt = params[0].startingAt;
                stoppingAt = params[0].stoppingAt;

                InputStream inputStream = createInputStream(startingAt, stoppingAt);
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                if (inputStream == null) {
                    Log.e("CacheStreamFactory","createInputStream returned null");
                    return new PreloadReturn(null,null);
                }
                try {
                    IOUtils.copy(inputStream, byteArrayOutputStream);
                    inputStream.close();
                } catch (IOException e) {
                    //e.printStackTrace();
                }

                byte[] payload = byteArrayOutputStream.toByteArray();
                synchronized(CacheStreamFactory.this.preloadTasks) {
                    // remove ourselves from the list before executing callbacks
                    CacheStreamFactory.this.preloadTasks.remove(this);
                }

                for(CachePreloadCallback callback : this.callbacks) {
                    callback.onLoadBackground(payload);
                }
                return new PreloadReturn(null,payload);
            }
        }

        @Override
        protected void onPostExecute(PreloadReturn params) {
            super.onPostExecute(params);
            Helpers.debugLog("CacheStreamFactory", CacheStreamFactory.this+"->preload()->onPostExecute("+((params==null)?"null":"not-null")+")");
            synchronized(CacheStreamFactory.this.preloadTasks) {
                // remove ourselves from the list before executing callbacks
                CacheStreamFactory.this.preloadTasks.remove(this);
            }
            //CachePreloadCallback callback = params.callback;
            // on network failure params.payload will be null
            byte[] payload = params.payload;
            //Helpers.debugLog("CacheStreamFactory", CacheStreamFactory.this+"->preload()->onPostExecute("+((callback==null)?"null":"not-null")+")");
            //if (callback != null) {
            for(CachePreloadCallback callback : this.callbacks) {
                callback.onLoad(payload);
            }
            //}
        }
    }

    void cancel(boolean mayInterruptIfRunning) {
        Helpers.debugLog("CacheStreamFactory", "cancelling callbacks");
        synchronized (preloadTasks) {
            for(PreloadTask preloadTask : preloadTasks.values()) {
                preloadTask.callbacks.clear();
            }
        }
    }

    void preload(CachePreloadCallback callback) {
        preload(null, null, callback);
    }

    void preload(String startingAt, String stoppingAt, CachePreloadCallback callback) {
        Helpers.debugLog("CacheStreamFactory", this+"->preload(...,"+startingAt+","+stoppingAt+")");


        //do we already have a preload task matching these start/stop parameters?
        synchronized (preloadTasks) {
            PreloadTask preloadTask = preloadTasks.get(startingAt + ":" + stoppingAt);
            if (preloadTask != null) {
                // yes we do
                // assume callbacks have not been called yet as it is still in the map
                preloadTask.callbacks.add(callback);

            } else {
                // no we don't
                // create new preload task

                preloadTask = new PreloadTask();
                // and add the callback
                preloadTask.callbacks.add(callback);
                //preloadTask.execute(new PreloadParameters(this,callback,startingAt,stoppingAt));

                // not sure how much of this is necessary now that we are handling multiple callbacks (listeners)

                try {
                    preloadTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, new PreloadParameters(this, null, startingAt, stoppingAt));
                } catch (RejectedExecutionException e) {
                    // FIXME: Fix multiple blocked threads from filling the pool
                    Log.e("CacheStreamFactory", "Too many threads... sobbing quietly and then ignoring your ridiculous request.");
                }
            }

        }
    }

    InputStream createInputStream() {
        return createInputStream(null,null);
    }

    InputStream createInputStream(String startingAt, String stoppingAt) {
        Helpers.debugLog("CacheStreamFactory",this+"->createInputStream("+startingAt+","+stoppingAt+")");
        synchronized (this) {
            Helpers.debugLog("CacheStreamFactory","unblocked!");
            if (stoppingAt != null && stoppingAt.equals(name)) {
                Log.e("CacheStreamFactory", "stoppingAt hit, returning null");
                return null;
            }
            if (startingAt == null || startingAt.equals(name)) {
                InputStream cis = createCacheInputStream();
                if (cis != null) {
                    Helpers.debugLog("CacheStreamFactory", this + ": cis!=null");
                    return cis;
                } else {
                    return wrappedFallbackStream(null, stoppingAt);
                }
            }
            return wrappedFallbackStream(startingAt, stoppingAt);
        }
    }

    // try separating the cache stream generation from the public input stream generation
    protected abstract InputStream createCacheInputStream();

    protected abstract OutputStream createCacheOutputStream();

    protected abstract void invalidateCache();

    public void invalidate() {
        invalidateCache();
        if (fallback!=null) {
            fallback.invalidate();
        }
    }

    private InputStream wrappedFallbackStream(String startingAt, String stoppingAt) {
        Helpers.debugLog("CacheStreamFactory", this+"->wrappedFallbackStream("+startingAt+", "+stoppingAt+")");
        if (fallback==null) {
            return null;
        }

        //final InputStream fallbackInputStream = new BufferedInputStream(fallback.createInputStream(startingAt, stoppingAt));
        //final OutputStream cacheOutputStream = new BufferedOutputStream(createCacheOutputStream());

        final InputStream fallbackInputStream = new BufferedInputStream(fallback.createInputStream(startingAt, stoppingAt));
        final OutputStream cacheOutputStream = new BufferedOutputStream(createCacheOutputStream());


        InputStream writeThroughStream = new InputStream() {

            @Override
            public int read() throws IOException {
                //Helpers.debugLog("writeThroughStream","read() called");
                int b = -1;
                try {
                    b = fallbackInputStream.read();
                    if(b>=0) {
                       cacheOutputStream.write(b);
                       //this breaks buffering but is sometimes used for testing
                       //cacheOutputStream.flush();
                    } else {
                        Log.e("writeThroughStream","fallbackInputStream.read() got "+b);
                    }
                }
                catch (IOException e) {
                    Log.e("writeThroughStream", "a wrapped stream got an exception");
                    e.printStackTrace();
                }
                return b;
            }

            @Override
            public long skip(long n) throws IOException {
                long skipped;
                Helpers.debugLog("writeThroughStream","skip("+n+") called");
                for(skipped = 0;skipped < n; skipped++) {
                    try {
                        read();
                    }
                    catch (IOException e) {
                        Log.e("writeThroughStream", "skip: exception during read");
                        e.printStackTrace();
                    }
                }
                return skipped;
            }

            @Override
            public void close() throws IOException {
                fallbackInputStream.close();
                cacheOutputStream.close();
            }

        };


        return writeThroughStream;

    }

    byte[] read() {
        return read(null,null);
    }

    // convenience method to mimick old ByteCache
    byte[] read(String startingAt, String stoppingAt) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try {
            InputStream inputStream = this.createInputStream(startingAt, stoppingAt);
            long c = IOUtils.copy(inputStream, byteArrayOutputStream);
            inputStream.close();
            Helpers.debugLog("CacheStreamFactory", this+": IOUtils.copy processed "+c+" bytes");
            return byteArrayOutputStream.toByteArray();
        } catch (IOException e) {
            Log.e("CacheStreamFactory", this+": IOException while reading stream to byte array");
        }
        return null;
    }
}

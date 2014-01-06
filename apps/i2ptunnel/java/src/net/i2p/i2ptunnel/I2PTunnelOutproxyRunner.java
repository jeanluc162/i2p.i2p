/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLException;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.util.ByteCache;
import net.i2p.util.Clock;
import net.i2p.util.I2PAppThread;
import net.i2p.util.InternalSocket;
import net.i2p.util.Log;

/**
 *  Like I2PTunnelRunner but socket-to-socket
 *
 *  @since 0.9.11
 */
public class I2PTunnelOutproxyRunner extends I2PAppThread {
    protected final Log _log;

    private static final AtomicLong __runnerId = new AtomicLong();
    private final long _runnerId;
    /** 
     * max bytes streamed in a packet - smaller ones might be filled
     * up to this size. Larger ones are not split (at least not on
     * Sun's impl of BufferedOutputStream), but that is the streaming
     * api's job...
     */
    static int MAX_PACKET_SIZE = 1024 * 4;

    static final int NETWORK_BUFFER_SIZE = MAX_PACKET_SIZE;

    private final Socket s;
    private final Socket i2ps;
    private final Object slock, finishLock = new Object();
    volatile boolean finished = false;
    private final byte[] initialI2PData;
    private final byte[] initialSocketData;
    /** when the last data was sent/received (or -1 if never) */
    private long lastActivityOn;
    /** when the runner started up */
    private final long startedOn;
    /** if we die before receiving any data, run this job */
    private final Runnable onTimeout;
    private long totalSent;
    private long totalReceived;

    private static final AtomicLong __forwarderId = new AtomicLong();
    
    /**
     *  Starts itself
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param onTimeout May be null. If non-null and no data (except initial data) was sent or received,
                         it will be run before closing s.
     */
    public I2PTunnelOutproxyRunner(Socket s, Socket i2ps, Object slock, byte[] initialI2PData,
                                   byte[] initialSocketData, Runnable onTimeout) {
        this.s = s;
        this.i2ps = i2ps;
        this.slock = slock;
        this.initialI2PData = initialI2PData;
        this.initialSocketData = initialSocketData;
        this.onTimeout = onTimeout;
        lastActivityOn = -1;
        startedOn = Clock.getInstance().now();
        _log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
        if (_log.shouldLog(Log.INFO))
            _log.info("I2PTunnelRunner started");
        _runnerId = __runnerId.incrementAndGet();
        setName("I2PTunnelOutproxyRunner " + _runnerId);
        start();
    }

    /** 
     * have we closed at least one (if not both) of the streams 
     * [aka we're done running the streams]? 
     *
     * @deprecated unused
     */
    public boolean isFinished() {
        return finished;
    }

    /** 
     * When was the last data for this runner sent or received?  
     *
     * @return date (ms since the epoch), or -1 if no data has been transferred yet
     * @deprecated unused
     */
    public long getLastActivityOn() {
        return lastActivityOn;
    }

    private void updateActivity() {
        lastActivityOn = Clock.getInstance().now();
    }

    /**
     * When this runner started up transferring data
     *
     */
    public long getStartedOn() {
        return startedOn;
    }

    protected InputStream getSocketIn() throws IOException { return s.getInputStream(); }
    protected OutputStream getSocketOut() throws IOException { return s.getOutputStream(); }
    
    @Override
    public void run() {
        try {
            InputStream in = getSocketIn();
            OutputStream out = getSocketOut();
            InputStream i2pin = i2ps.getInputStream();
            OutputStream i2pout = i2ps.getOutputStream();
            if (initialI2PData != null) {
                    i2pout.write(initialI2PData);
                    i2pout.flush();
            }
            if (initialSocketData != null) {
                // this does not increment totalReceived
                out.write(initialSocketData);
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Initial data " + (initialI2PData != null ? initialI2PData.length : 0) 
                           + " written to the outproxy, " + (initialSocketData != null ? initialSocketData.length : 0)
                           + " written to the socket, starting forwarders");
            if (!(s instanceof InternalSocket))
                in = new BufferedInputStream(in, 2*NETWORK_BUFFER_SIZE);
            Thread t1 = new StreamForwarder(in, i2pout, true);
            Thread t2 = new StreamForwarder(i2pin, out, false);
            synchronized (finishLock) {
                while (!finished) {
                    finishLock.wait();
                }
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("At least one forwarder completed, closing and joining");
            
            // this task is useful for the httpclient
            if (onTimeout != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("runner has a timeout job, totalReceived = " + totalReceived
                               + " totalSent = " + totalSent + " job = " + onTimeout);
                // Run even if totalSent > 0, as that's probably POST data.
                if (totalReceived <= 0)
                    onTimeout.run();
            }
            
            // now one connection is dead - kill the other as well, after making sure we flush
            close(out, in, i2pout, i2pin, s, i2ps, t1, t2);
        } catch (InterruptedException ex) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Interrupted", ex);
        } catch (SSLException she) {
            _log.error("SSL error", she);
        } catch (IOException ex) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Error forwarding", ex);
        } catch (IllegalStateException ise) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("gnu?", ise);
        } catch (Exception e) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Internal error", e);
        } finally {
            try {
                if (s != null)
                    s.close();
            } catch (IOException ex) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Could not close java socket", ex);
            }
            if (i2ps != null) {
                try {
                    i2ps.close();
                } catch (IOException ex) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Could not close Socket", ex);
                }
            }
        }
    }
    
    protected void close(OutputStream out, InputStream in, OutputStream i2pout, InputStream i2pin,
                         Socket s, Socket i2ps, Thread t1, Thread t2) throws InterruptedException {
        try { 
            out.flush(); 
        } catch (IOException ioe) { 
            // ignore
        }
        try { 
            i2pout.flush();
        } catch (IOException ioe) {
            // ignore
        }
        try { 
            in.close();
        } catch (IOException ioe) { 
            // ignore
        }
        try { 
            i2pin.close();
        } catch (IOException ioe) { 
            // ignore
        }
        // ok, yeah, there's a race here in theory, if data comes in after flushing and before
        // closing, but its better than before...
        try { 
            s.close();
        } catch (IOException ioe) { 
            // ignore
        }
        try { 
            i2ps.close();
        } catch (IOException ioe) { 
            // ignore
        }
        t1.join(30*1000);
        t2.join(30*1000);
    }
    
    public void errorOccurred() {
        synchronized (finishLock) {
            finished = true;
            finishLock.notifyAll();
        }
    }
    
    private class StreamForwarder extends I2PAppThread {

        private final InputStream in;
        private final OutputStream out;
        private final String direction;
        private final boolean _toI2P;
        private final ByteCache _cache;

        private StreamForwarder(InputStream in, OutputStream out, boolean toI2P) {
            this.in = in;
            this.out = out;
            _toI2P = toI2P;
            direction = (toI2P ? "toI2P" : "fromI2P");
            _cache = ByteCache.getInstance(32, NETWORK_BUFFER_SIZE);
            setName("StreamForwarder " + _runnerId + '.' + __forwarderId.incrementAndGet());
            start();
        }

        @Override
        public void run() {
            String from = "todo";
            String to = "todo";

            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug(direction + ": Forwarding between " 
                           + from + " and " + to);
            }
            
            ByteArray ba = _cache.acquire();
            byte[] buffer = ba.getData(); // new byte[NETWORK_BUFFER_SIZE];
            try {
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                    if (_toI2P)
                        totalSent += len;
                    else
                        totalReceived += len;

                    if (len > 0) updateActivity();

                    if (in.available() == 0) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(direction + ": " + len + " bytes flushed through " + (_toI2P ? "to " : "from ")
                                       + "outproxy");
                        try {
                            Thread.sleep(I2PTunnel.PACKET_DELAY);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        
                        if (in.available() <= 0)
                            out.flush(); // make sure the data get though
                    }
                }
                //out.flush(); // close() flushes
            } catch (SocketException ex) {
                // this *will* occur when the other threads closes the socket
                synchronized (finishLock) {
                    if (!finished) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(direction + ": Socket closed - error reading and writing",
                                       ex);
                    }
                }
            } catch (InterruptedIOException ex) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(direction + ": Closing connection due to timeout (error: \""
                              + ex.getMessage() + "\")");
            } catch (IOException ex) {
                if (!finished) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(direction + ": Error forwarding", ex);
                }
            } finally {
                _cache.release(ba);
                if (_log.shouldLog(Log.INFO)) {
                    _log.info(direction + ": done forwarding between " 
                              + from + " and " + to);
                }
                try {
                    in.close();
                } catch (IOException ex) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(direction + ": Error closing input stream", ex);
                }
                try {
                    if (!(onTimeout != null && (!_toI2P) && totalReceived <= 0))
                        out.close();
                    else if (_log.shouldLog(Log.INFO))
                        _log.info(direction + ": not closing so we can write the error message");
                } catch (IOException ioe) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(direction + ": Error flushing to close", ioe);
                }
                synchronized (finishLock) {
                    finished = true;
                    finishLock.notifyAll();
                    // the main thread will close sockets etc. now
                }
            }
        }
    }
}

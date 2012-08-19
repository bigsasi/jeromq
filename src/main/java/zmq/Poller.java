package zmq;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class Poller extends PollerBase implements Runnable {

    //  This table stores data for registered descriptors.
    final private Map<SelectableChannel, IPollEvents> fd_table;

    //  Pollset to pass to the poll function.
    final private Map<SelectableChannel, Integer> pollset;

    //  If true, there's at least one retired event source.
    volatile private boolean retired;

    //  If true, thread is in the process of shutting down.
    volatile private boolean stopping;
    volatile public boolean stopped;
    
    private Thread worker;
    final private Selector selector;
    final private String name;
    
    public Poller(String name_) {
        
        name = name_;
        retired = false;
        stopping = false;
        stopped = false;
        
        fd_table = new HashMap<SelectableChannel, IPollEvents>();
        pollset = new HashMap<SelectableChannel, Integer>();
        try {
            selector = Selector.open();
        } catch (IOException e) {
            throw new ZException.IOException(e);
        }
    }

    public void add_fd (SelectableChannel fd_, IPollEvents events_)
    {
        
        fd_table.put(fd_, events_);
        pollset.put(fd_, 0);
        
        adjust_load (1);
        
    }
    

    public void rm_fd(SelectableChannel handle) {
        
        fd_table.remove(handle);
        pollset.remove(handle);
        retired = true;

        selector.wakeup();
        //  Decrease the load metric of the thread.
        adjust_load (-1);
    }
    

    public void set_pollin (SelectableChannel handle_)
    {
        register(handle_, SelectionKey.OP_READ);
    }
    

    public void reset_pollin (SelectableChannel handle_) {
        register(handle_, SelectionKey.OP_READ, true);
    }
    
    public void set_pollout (SelectableChannel handle_)
    {
        register(handle_,  SelectionKey.OP_WRITE);
    }
    
    public void reset_pollout (SelectableChannel handle_) {
        register(handle_, SelectionKey.OP_WRITE, true);
    }

    public void set_pollconnect(SelectableChannel handle_) {
        register(handle_, SelectionKey.OP_CONNECT);
    }
    
    public void set_pollaccept(SelectableChannel handle_) {
        register(handle_, SelectionKey.OP_ACCEPT);        
    }

    private void register (SelectableChannel handle_, int ops) {
        register (handle_, ops, false);
    }

    private void register (SelectableChannel handle_, int ops, boolean negate)
    {

        
        if (negate) 
            ops = pollset.get(handle_) &~ ops;
        else
            ops = pollset.get(handle_) | ops;
        pollset.put(handle_, ops);
        
        retired = true;
        selector.wakeup();

    }
    
    public void start() {
        worker = new Thread(this, name);
        worker.start();
    }
    
    public void stop() {
        stopping = true;
        
        try {
            selector.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        worker.interrupt();

    }
    
    


    @Override
    public void run() {
        
        while (!stopping) {

            //  Execute any due timers.
            long timeout = execute_timers ();
            
            if (retired) {
                
                for (SelectableChannel ch: fd_table.keySet()) {
                    SelectionKey key = ch.keyFor(selector);
                    if (key == null) {
                        try {
                            key = ch.register(selector, pollset.get(ch));
                            key.attach(fd_table.get(ch));
                        } catch (ClosedChannelException e) {
                            continue;
                        }
                    } else if (key.isValid()) {
                        key.interestOps(pollset.get(ch));
                    }
                }
                for (SelectionKey key: selector.keys()) {
                    
                    if (!fd_table.containsKey(key.channel())) {
                        // removed
                        key.cancel();
                    } 
                }
                retired = false;
                
            }

            //  Wait for events.
            int rc;
            try {
                rc = selector.select(timeout);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            
            if (rc == 0) 
                continue;
            
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                
                SelectionKey key = it.next();
                IPollEvents evt = (IPollEvents) key.attachment();
                it.remove();
                
                if (!key.isValid() ) {
                    throw new RuntimeException("invalid");
                }
                

                if (key.isAcceptable()) {
                    evt.accept_event();
                } else  if (key.isReadable() ) {
                    evt.in_event();
                } else if (key.isWritable()) {
                    evt.out_event();
                } else if (key.isConnectable()) {
                    evt.connect_event();
                } 

            }
            //selector.selectedKeys().clear();

        }
        stopped = true;
        
    }


    
}

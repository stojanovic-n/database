/*

Copyright (C) SYSTAP, LLC 2006-2008.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
/*
 * Created on Mar 28, 2008
 */

package com.bigdata.service.jini;

import java.io.IOException;
import java.io.Serializable;
import java.rmi.Remote;
import java.rmi.server.ExportException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import net.jini.config.Configuration;
import net.jini.core.discovery.LookupLocator;
import net.jini.core.lookup.ServiceItem;
import net.jini.core.lookup.ServiceRegistrar;
import net.jini.discovery.DiscoveryEvent;
import net.jini.discovery.DiscoveryListener;
import net.jini.discovery.LookupDiscoveryManager;
import net.jini.export.Exporter;
import net.jini.jeri.BasicILFactory;
import net.jini.jeri.BasicJeriExporter;
import net.jini.jeri.InvocationLayerFactory;
import net.jini.jeri.tcp.TcpServerEndpoint;
import net.jini.lookup.ServiceDiscoveryEvent;
import net.jini.lookup.ServiceDiscoveryListener;

import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.KeeperException.NoNodeException;

import com.bigdata.btree.IRangeQuery;
import com.bigdata.io.IStreamSerializer;
import com.bigdata.jini.start.config.ZookeeperClientConfig;
import com.bigdata.journal.IResourceLockService;
import com.bigdata.journal.ITransactionService;
import com.bigdata.relation.accesspath.IAccessPath;
import com.bigdata.relation.accesspath.IAsynchronousIterator;
import com.bigdata.relation.accesspath.IBuffer;
import com.bigdata.service.AbstractDistributedFederation;
import com.bigdata.service.AbstractFederation;
import com.bigdata.service.IDataService;
import com.bigdata.service.ILoadBalancerService;
import com.bigdata.service.IMetadataService;
import com.bigdata.service.IService;
import com.bigdata.service.jini.DataServer.AdministrableDataService;
import com.bigdata.service.jini.LoadBalancerServer.AdministrableLoadBalancer;
import com.bigdata.service.jini.MetadataServer.AdministrableMetadataService;
import com.bigdata.service.proxy.ClientAsynchronousIterator;
import com.bigdata.service.proxy.ClientBuffer;
import com.bigdata.service.proxy.ClientFuture;
import com.bigdata.service.proxy.RemoteAsynchronousIterator;
import com.bigdata.service.proxy.RemoteAsynchronousIteratorImpl;
import com.bigdata.service.proxy.RemoteBuffer;
import com.bigdata.service.proxy.RemoteBufferImpl;
import com.bigdata.service.proxy.RemoteFuture;
import com.bigdata.service.proxy.RemoteFutureImpl;
import com.bigdata.zookeeper.ZooResourceLockService;
import com.sun.jini.admin.DestroyAdmin;

/**
 * Concrete implementation for Jini.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class JiniFederation extends AbstractDistributedFederation implements
        DiscoveryListener, ServiceDiscoveryListener, Watcher {

    protected DataServicesClient dataServicesClient;

    protected LoadBalancerClient loadBalancerClient;

    protected ZooResourceLockService resourceLockService = new ZooResourceLockService(this);

    protected TransactionServiceClient transactionServiceClient;

    protected ServicesManagerClient servicesManagerClient;

    protected LookupDiscoveryManager lookupDiscoveryManager;

    protected ZooKeeper zookeeper;
    
    private final ZookeeperClientConfig zooConfig;
    
    /**
     * Return the zookeeper client configuration.
     */
    public ZookeeperClientConfig getZooConfig() {
        
        return zooConfig;
        
    }
    
    /**
     * Return the zookeeper client.
     */
    public ZooKeeper getZookeeper() {
        
        return zookeeper;
        
    }
    
    /**
     * The object used to manage registrar discovery (basically, the "client"
     * class used to talk to jini). You won't need this unless you are already
     * fairly familiar with jini. All the well know service discovery operations
     * are handled by a variety of caching classes such as
     * {@link #getDataServicesClient()} and exposed via simple APIs such as
     * {@link #getDataService(UUID)}.
     */
    public LookupDiscoveryManager getDiscoveryManagement() {
        
        return lookupDiscoveryManager;
        
    }
    
    /**
     * Cached lookup for discovered {@link IDataService}s and
     * {@link IMetadataService}s. Will block on a cache miss and attempt to
     * discover an appropriate service instance.
     */
    public DataServicesClient getDataServicesClient() {
        
        return dataServicesClient;
        
    }
    
    /**
     * Initiaties discovery for one or more service registrars and establishes a
     * lookup caches for various bigdata services.
     * 
     * @param client
     *            The client.
     */
    public JiniFederation(final JiniClient client, final JiniClientConfig jiniConfig,
            final ZookeeperClientConfig zooConfig) {

        super(client);
    
        open = true;

        // @todo config initalDelay and timeout
        addScheduledTask(new MonitorFuturesTask(futures), 1, 5, TimeUnit.SECONDS);

        if (INFO)
            log.info(jiniConfig.toString());
        
        final String[] groups = jiniConfig.groups;
        
        final LookupLocator[] lookupLocators = jiniConfig.locators;

        try {

            /*
             * Connect to a zookeeper service in the declare ensemble of
             * zookeeper servers.
             * 
             * @todo if the zookeeper integration is to be optional then we can
             * not insist on the zooconfig an this section needs to be
             * conditional on whether or not zookeeper was configured.
             */
            
            this.zooConfig = zooConfig;

            zookeeper = new ZooKeeper(zooConfig.servers,
                    zooConfig.sessionTimeout, this/* watcher */);
            
            /*
             * Note: This class will perform multicast discovery if ALL_GROUPS
             * is specified and otherwise requires you to specify one or more
             * unicast locators (URIs of hosts running discovery services). As
             * an alternative, you can use LookupDiscovery, which always does
             * multicast discovery.
             */
            lookupDiscoveryManager = new LookupDiscoveryManager(groups,
                    lookupLocators, this /* DiscoveryListener */, client
                            .getConfiguration()
            );

            final long cacheMissTimeout = Long.valueOf(client.getProperties()
                    .getProperty(JiniClient.Options.CACHE_MISS_TIMEOUT,
                            JiniClient.Options.DEFAULT_CACHE_MISS_TIMEOUT));

            // Start discovery for data and metadata services.
            dataServicesClient = new DataServicesClient(lookupDiscoveryManager, this,
                    cacheMissTimeout);

            // Start discovery for the timestamp service.
            transactionServiceClient = new TransactionServiceClient(
                    lookupDiscoveryManager, this, cacheMissTimeout);

            // Start discovery for the load balancer service.
            loadBalancerClient = new LoadBalancerClient(lookupDiscoveryManager, this,
                    cacheMissTimeout);

            // Start discovery for the services manager.
            servicesManagerClient = new ServicesManagerClient(lookupDiscoveryManager, this,
                    cacheMissTimeout);

        } catch (Exception ex) {

            log.fatal("Problem initiating service discovery: "
                    + ex.getMessage(), ex);

            try {

                shutdownNow();
                
            } catch (Throwable t) {
                
                log.error(t.getMessage(), t);
                
            }

            throw new RuntimeException(ex);
            
        }
        
    }

//    /**
//     * Wait for zookeeper and jini to become connected.
//     * <p>
//     * Note: This is event driven so it will not wait any longer than necessary.
//     * 
//     * @param timeout
//     * @param unit
//     * 
//     * @return <code>true</code> if the preconditions are satisified.
//     * 
//     * @throws InterruptedException
//     */
//    protected boolean awaitPreconditions(final long timeout, final TimeUnit unit)
//            throws InterruptedException {
//
//        final long begin = System.nanoTime();
//
//        long nanos = TimeUnit.MILLISECONDS.toNanos(timeout);
//
//        // await jini registrar(s)
//        if (!awaitJiniRegistrars(nanos, TimeUnit.NANOSECONDS)) {
//
//            log.error("No jini registrars.");
//
//            return false;
//
//        }
//
//        nanos -= (System.nanoTime() - begin);
//
//        // await zookeeper connection.
//        if (!awaitZookeeperConnected(nanos, TimeUnit.NANOSECONDS)) {
//
//            log
//                    .error("Zookeeper not connected : state="
//                            + zookeeper.getState());
//
//            return false;
//
//        }
//
//        return true;
//        
//    }
    
    /**
     * Await at least one jini {@link ServiceRegistrar} to be discovered.
     * 
     * @param timeout
     * @param unit
     * 
     * @return <code>true</code> at least one registrar was available before
     *         the timeout.
     * 
     * @throws InterruptedException
     *             if interrupted awaiting a jini {@link ServiceRegistrar} to be
     *             discovered.
     */
    public boolean awaitJiniRegistrars(final long timeout, final TimeUnit unit)
            throws InterruptedException {
        
        final long begin = System.nanoTime();
        
        // nanoseconds remaining.
        long nanos = unit.toNanos(timeout);

        ServiceRegistrar[] registrars = null;

        while (((registrars = lookupDiscoveryManager.getRegistrars()).length == 0)
                && (nanos -= (System.nanoTime() - begin)) > 0) {

            // wait a bit, but not more than the time remaining.
            discoveryEventLock.lockInterruptibly();
            try {
                
                discoveryEvent.awaitNanos(nanos);

            } finally {
            
                discoveryEventLock.unlock();
                
            }

        }

        if (registrars.length == 0) {

            final long elapsed = System.nanoTime() - begin;

            log.warn("jini: no registrars: elapsed="
                    + TimeUnit.NANOSECONDS.toMillis(elapsed));

            return false;

        }
        
        // at least one registrar.
        return true;

    }
    
    /**
     * Await {@link ZooKeeper} to be {@link ZooKeeper.States#CONNECTED}, but
     * not more than the specified timeout.
     * 
     * @param timeout
     * @param unit
     * 
     * @return <code>true</code> iff we noticed {@link ZooKeeper} entering the
     *         connected state before the timeout.
     * 
     * @throws InterruptedException
     *             if interrupted awaiting the {@link ZooKeeper} client to be
     *             connected.
     * @throws IllegalStateException
     *             if the {@link ZooKeeper} client does not exist.
     */
    public boolean awaitZookeeperConnected(final long timeout, final TimeUnit unit)
            throws InterruptedException {

        if (zookeeper == null) {

            throw new IllegalStateException("No zookeeper client?");

        }

        final long begin = System.nanoTime();
        
        // nanoseconds remaining.
        long nanos = unit.toNanos(timeout);

        ZooKeeper.States state = null;

        while ((nanos -= (System.nanoTime() - begin)) > 0) {

            switch (state = zookeeper.getState()) {
            case CONNECTED:
                return true;
            case AUTH_FAILED:
                log.error("Zookeeper authorization failure.");
                break;
            default:
                // wait a bit, but not more than the time remaining.
                zookeeperEventLock.lockInterruptibly();
                try {
                    zookeeperEvent.awaitNanos(nanos);
                } finally {
                    zookeeperEventLock.unlock();
                }
            }

        }

        final long elapsed = System.nanoTime() - begin;

        log.warn("Zookeeper: not connected: state=" + state + ", elapsed="
                + TimeUnit.NANOSECONDS.toMillis(elapsed));

        return false;

    }

    public JiniClient getClient() {

        return (JiniClient) super.getClient();
        
    }
    
    public ILoadBalancerService getLoadBalancerService() {

        // Note: return null if service not available/discovered.
        if (loadBalancerClient == null)
            return null;

        return loadBalancerClient.getLoadBalancerService();

    }

    public ITransactionService getTransactionService() {

        // Note: return null if service not available/discovered.
        if (transactionServiceClient == null)
            return null;

        return transactionServiceClient.getTransactionService();

    }
    
    public IResourceLockService getResourceLockService() {
        
        return resourceLockService;
        
    }
    
    public IMetadataService getMetadataService() {

        // Note: return null if service not available/discovered.
        if (dataServicesClient == null)
            return null;

        return dataServicesClient.getMetadataService();

    }

    public UUID[] getDataServiceUUIDs(int maxCount) {

        assertOpen();

        return dataServicesClient.getDataServiceUUIDs(maxCount);

    }

    public IDataService getDataService(UUID serviceUUID) {

        // Note: return null if service not available/discovered.
        if (dataServicesClient == null)
            return null;

        return dataServicesClient.getDataService(serviceUUID);

    }
    
    public IDataService getAnyDataService() {

        assertOpen();

        return dataServicesClient.getDataService();
        
    }

    public IDataService getDataServiceByName(final String name) {
        
        // Note: no services are available/discovered.
        if (dataServicesClient == null)
            return null;

        return dataServicesClient.getDataServiceByName(name);
        
    }

    private boolean open;
    
    synchronized public void shutdown() {
        
        if(!open) return;
        
        open = false;
        
        final long begin = System.currentTimeMillis();

        if (INFO)
            log.info("begin");

        super.shutdown();

        terminateDiscoveryProcesses();

        final long elapsed = System.currentTimeMillis() - begin;

        if (INFO)
            log.info("Done: elapsed=" + elapsed + "ms");

    }

    synchronized public void shutdownNow() {

        if(!open) return;
        
        open = false;

        final long begin = System.currentTimeMillis();

        if (INFO)
            log.info("begin");
        
        super.shutdownNow();
        
        terminateDiscoveryProcesses();
        
        final long elapsed = System.currentTimeMillis() - begin;
        
        if (INFO)
            log.info("Done: elapsed=" + elapsed + "ms");

    }

    /**
     * Stop various discovery processes.
     */
    private void terminateDiscoveryProcesses() {
        
        if (transactionServiceClient != null) {

            transactionServiceClient.terminate();

            transactionServiceClient = null;
            
        }
        
        if (loadBalancerClient != null) {

            loadBalancerClient.terminate();

            loadBalancerClient = null;
            
        }
        
        if (dataServicesClient != null) {

            dataServicesClient.terminate();

            dataServicesClient = null;
            
        }

        if (lookupDiscoveryManager != null) {

            lookupDiscoveryManager.terminate();

            lookupDiscoveryManager = null;

        }

        if (zookeeper != null) {
            
            try {

                // close the zookeeper client.
                zookeeper.close();

            } catch (InterruptedException e) {

                throw new RuntimeException(e);

            }

            zookeeper = null;

        }

        // discard all zookeeper watchers.
        watchers.clear();
        
    }

    private static final String ERR_RESOLVE = "Could not resolve: ";

    private static final String ERR_DESTROY_ADMIN = "Could not destroy: ";

    /**
     * Shutdown the services in the distributed federation <strong>NOT</strong>
     * just your client. This method may be used to take the entire federation
     * out of service. All services will be halted and all clients will be
     * disconnected. If you only wish to disconnect from a federation, then use
     * {@link #shutdown()} or {@link #shutdownNow()} instead.
     * <p>
     * The shutdown protocol is as follows:
     * <ol>
     * <li>{@link ITransactionService} (blocks until shutdown).</li>
     * <li>{@link IDataService}s (blocks until all are shutdown).</li>
     * <li>{@link IMetadataService}</li>
     * <li>{@link ILoadBalancerService}</li>
     * <li>{@link IResourceLockService}</li>
     * </ol>
     * 
     * @param immediate
     *            When <code>true</code> the services will be shutdown without
     *            waiting for existing transactions and other tasks to
     *            terminate.
     *            
     * @throws InterruptedException 
     */
    public void distributedFederationShutdown(final boolean immediate)
            throws InterruptedException {

        assertOpen();

        // shutdown the data services.
        {

            final UUID[] a = dataServicesClient
                    .getDataServiceUUIDs(0/* maxCount */);

            final List<Callable<Void>> tasks = new ArrayList<Callable<Void>>(a.length);
            
            for (UUID uuid : a) {
                
                final UUID tmp = uuid;
                
                tasks.add(new Callable<Void>() {
                
                    public Void call() throws Exception {
                        
                        final AdministrableDataService service = (AdministrableDataService) getDataService(tmp);

                        if (service != null) {

                            try {

                                if (immediate)
                                    service.shutdownNow();
                                else
                                    service.shutdown();

                            } catch (Throwable t) {

                                log.error(t.getMessage(), t);

                            }

                        }
                        
                        return null;
                        
                    }
                    
                });
                
            }
            
            // blocks until all data services are down.
            getExecutorService().invokeAll(tasks);

        }

        // shutdown the metadata service.
        {

            final AdministrableMetadataService service = (AdministrableMetadataService) getMetadataService();

            try {

                if (immediate)
                    service.shutdownNow();
                else
                    service.shutdown();

            } catch (Throwable t) {

                log.error(t.getMessage(), t);

            }

        }

        // shutdown the load balancer
        {

            final AdministrableLoadBalancer service = (AdministrableLoadBalancer) getLoadBalancerService();

            try {

                if (immediate)
                    service.shutdownNow();
                else
                    service.shutdown();

            } catch (Throwable t) {

                log.error(t.getMessage(), t);

            }

        }

    }

    public void destroy() {

        assertOpen();

        /*
         * Destroy services managers.
         * 
         * @todo we could handle all of them a bit more like this.
         * 
         * @todo it would be easy enough to write a shutdownAll() method as
         * well.
         */
        if (servicesManagerClient != null) {

//            Object service;
//            while ((service = servicesManagerClient.getService()) != null) {
//
//                try {
//
//                    System.err.println("Will destroy: " + service);
//
//                    ((RemoteDestroyAdmin) (service)).destroy();
//
//                } catch (IOException e) {
//
//                    log.error(ERR_DESTROY_ADMIN + service, e);
//
//                }
//                
//            }
            
            // force a lookup.
            servicesManagerClient.getService();
            
            final ServiceCache cache = servicesManagerClient.getServiceCache();
            
            // return everything in the cache.
            final ServiceItem[] items = cache.getServiceItems(0/* maxCount */,
                    null/* filter */);

            for (ServiceItem item : items) {

                try {
                    
                    log.warn("Will destroy: "+item);

                    ((RemoteDestroyAdmin) (item.service)).destroy();

                } catch (IOException e) {

                    log.error(ERR_DESTROY_ADMIN + item, e);

                }

            }

        }

        // destroy the transaction service(s).
        if (transactionServiceClient != null) {

            final ITransactionService service = transactionServiceClient
                    .getTransactionService();

            if (service != null) {

                try {

                    log.warn("Will destroy: " + service);

                    ((DestroyAdmin) service).destroy();

                } catch (IOException e) {

                    log.error(ERR_DESTROY_ADMIN + service, e);

                }

            }
            
        }

        // destroy data services.
        if (dataServicesClient != null) {

            final UUID[] uuids = dataServicesClient.getDataServiceUUIDs(0);

            for (UUID uuid : uuids) {

                final IDataService service;

                try {

                    service = getDataService(uuid);

                } catch (Exception ex) {

                    log.error(ERR_RESOLVE + uuid);

                    continue;

                }

                try {

                    log.warn("Will destroy: "+service);

                    service.destroy();

                } catch (IOException e) {

                    log.error(ERR_DESTROY_ADMIN + service, e);

                }

            }

        }

        // destroy metadata services.
        if (dataServicesClient != null) {

            final IMetadataService service = dataServicesClient
                    .getMetadataService();

            if (service != null) {

                try {

                    log.warn("Will destroy: "+service);

                    service.destroy();

                } catch (IOException e) {

                    log.error(ERR_DESTROY_ADMIN + service, e);

                }

            }

        }

        // destroy load balancer(s)
        if (loadBalancerClient != null) {

            final ILoadBalancerService service = loadBalancerClient
                    .getLoadBalancerService();

            if (service != null) {

                try {

                    log.warn("Will destroy: "+service);

                    ((DestroyAdmin) service).destroy();

                } catch (IOException e) {

                    log.error(ERR_DESTROY_ADMIN + service, e);

                }

            }

        }

        if (zookeeper != null) {

            try {

                /*
                 * Clear out everything in zookeeper for this federation.
                 */
                if (zookeeper.exists(zooConfig.zroot, false/* watch */) != null) {

                    destroyZNodes(zookeeper, zooConfig.zroot, 0/* depth */);

                }
                
            } catch (Exception e) {
                
                throw new RuntimeException(e);
                
            }
            
        }

    }

    private void destroyZNodes(final ZooKeeper z, final String zpath,
            final int depth) throws KeeperException,
            InterruptedException {

//        System.err.println("enter : " + zpath);

        final List<String> children;
        try {

            children = z.getChildren(zpath, false);
            
        } catch (NoNodeException ex) {
            
            // node is gone.
            return;
        }

        for (String child : children) {

            destroyZNodes(z, zpath + "/" + child, depth + 1);

        }

        if(INFO)
            log.info("delete: " + zpath);

        try {
            z.delete(zpath, -1/* version */);

        } catch(NoNodeException ex) {
        
            // node is gone.
            
        }
        
    }
    
    public long getLastCommitTime() {

        final ITransactionService transactionService = getTransactionService();

        if (transactionService != null) {

            try {
                
                lastKnownCommitTime = transactionService.getLastCommitTime();
                
            } catch (IOException e) {
                
                log.error("Can not reach the timestampService?", e);
                
                // fall through - report the lastKnownCommitTime.
                
            }

        }

        return lastKnownCommitTime;

    }
    private long lastKnownCommitTime;

    /**
     * Note: The invocation layer factory is reused for each exported proxy (but
     * the exporter itself is paired 1:1 with the exported proxy).
     */
    private InvocationLayerFactory invocationLayerFactory = new BasicILFactory();
    
    /**
     * Return an {@link Exporter} for a single object that implements one or
     * more {@link Remote} interfaces.
     * <p>
     * Note: This uses TCP Server sockets.
     * <p>
     * Note: This uses [port := 0], which means a random port is assigned.
     * <p>
     * Note: The VM WILL NOT be kept alive by the exported proxy (keepAlive is
     * <code>false</code>).
     * 
     * @param enableDGC
     *            if distributed garbage collection should be used for the
     *            object to be exported.
     * 
     * @return The {@link Exporter}.
     */
    protected Exporter getExporter(final boolean enableDGC) {
        
        /*
         * Setup the Exporter for the iterator.
         * 
         * Note: Distributed garbage collection is enabled since the proxied
         * future CAN become locally weakly reachable sooner than the client can
         * get() the result. Distributed garbage collection handles this for us
         * and automatically unexports the proxied iterator once it is no longer
         * strongly referenced by the client.
         */
        
        return new BasicJeriExporter(TcpServerEndpoint
                .getInstance(0/* port */), invocationLayerFactory, enableDGC,
                false/* keepAlive */);
        
    }
    
    /**
     * Export and return a proxy object.
     * <p>
     * Note: This is used for query evaluation in which there is at least one
     * JOIN. It DOES NOT get used for simple {@link IAccessPath} scans. Those
     * use {@link IRangeQuery#rangeIterator()} instead.
     * 
     * @todo Allow {@link Configuration} of the {@link Exporter} for the proxy
     *       iterators and futures.
     *       <p>
     *       Since the {@link Exporter} is paired to a single object the
     *       configuration of the {@link Exporter} will require an additional
     *       level of indirection when compared to the {@link Configuration} of
     *       an {@link AbstractServer}'s {@link Exporter}.
     */
    @Override
    public <E> IAsynchronousIterator<E> getProxy(
            final IAsynchronousIterator<E> sourceIterator,
            final IStreamSerializer<E> serializer,
            final int capacity) {
        
        if (sourceIterator == null)
            throw new IllegalArgumentException();

        if (serializer == null)
            throw new IllegalArgumentException();

        if (capacity <= 0)
            throw new IllegalArgumentException();

//        if (sourceIterator.hasNext(1L, TimeUnit.MILLISECONDS)
//                && sourceIterator.isFutureDone()) {
//            
//            /*
//             * @todo If the producer is done we can materialize all of the data
//             * in a thick iterator, ResultSet or RemoteChunk and send that back.
//             * [probably do not wait at all, but just test isFutureDone].
//             */
//            
//            log.warn("Async iterator is done: modify code to send back fully materialized chunk.");
//            
//        }
        
        /*
         * Setup the Exporter for the iterator.
         * 
         * Note: Distributed garbage collection is enabled since the proxied
         * iterator CAN become locally weakly reachable sooner than the client
         * can close() the iterator (or perhaps even consume the iterator!)
         * Distributed garbage collection handles this for us and automatically
         * unexports the proxied iterator once it is no longer strongly
         * referenced by the client.
         */
        final Exporter exporter = getExporter(true/* enableDCG */);
        
        // wrap the iterator with an exportable object.
        final RemoteAsynchronousIterator<E> impl = new RemoteAsynchronousIteratorImpl<E>(
                sourceIterator
                , serializer
                );
        
        /*
         * Export and return the proxy.
         */
        final RemoteAsynchronousIterator<E> proxy;
        try {

            // export proxy.
            proxy = (RemoteAsynchronousIterator<E>) exporter.export(impl);

        } catch (ExportException ex) {

            throw new RuntimeException("Export error: " + ex, ex);
            
        }

        /*
         * Wrap the proxy object in a serializable object so that it looks like
         * an IChunkedOrderedIterator and return it to the caller.
         */

        return new ClientAsynchronousIterator<E>(proxy, capacity);

    }

    /**
     * Note that {@link Future}s generated by <code>java.util.concurrent</code>
     * are NOT {@link Serializable}. Futher note the proxy as generated by an
     * {@link Exporter} MUST be encapsulated so that the object returned to the
     * caller can implement {@link Future} without having to declare that the
     * methods throw {@link IOException} (for RMI).
     * 
     * @param future
     *            The future.
     * 
     * @return A proxy for that {@link Future} that masquerades any RMI
     *         exceptions.
     */
    public <E> Future<E> getProxy(final Future<E> future) {

        /*
         * Setup the Exporter for the Future.
         * 
         * Note: Distributed garbage collection is enabled since the proxied
         * future CAN become locally weakly reachable sooner than the client can
         * get() the result. Distributed garbage collection handles this for us
         * and automatically unexports the proxied iterator once it is no longer
         * strongly referenced by the client.
         */
        final Exporter exporter = getExporter(true/* enableDGC */);
        
        // wrap the future in a proxyable object.
        final RemoteFuture<E> impl = new RemoteFutureImpl<E>(future);

        /*
         * Export the proxy.
         */
        final RemoteFuture<E> proxy;
        try {

            // export proxy.
            proxy = (RemoteFuture<E>) exporter.export(impl);

            if (INFO) {

                log.info("Exported proxy: proxy=" + proxy + "("
                        + proxy.getClass() + ")");

            }

        } catch (ExportException ex) {

            throw new RuntimeException("Export error: " + ex, ex);

        }

        // return proxy to caller.
        return new ClientFuture<E>(proxy);

    }

    /**
     * A proxy for an {@link IBuffer} that does not extend {@link Remote} and
     * which DOES NOT declare that its methods throw {@link IOException} (for
     * RMI).
     * 
     * @param buffer
     *            The future.
     * 
     * @return A proxy for that {@link IBuffer} that masquerades any RMI
     *         exceptions.
     */
    public <E> IBuffer<E> getProxy(final IBuffer<E> buffer) {
        
        /*
         * Setup the Exporter.
         */
        final Exporter exporter = getExporter(true/* enableDGC */);
        
        // wrap in a proxyable object.
        final RemoteBuffer<E> impl = new RemoteBufferImpl<E>(buffer);

        /*
         * Export the proxy.
         */
        final RemoteBuffer<E> proxy;
        try {

            // export proxy.
            proxy = (RemoteBuffer<E>) exporter.export(impl);

            if (INFO) {

                log.info("Exported proxy: proxy=" + proxy + "("
                        + proxy.getClass() + ")");

            }

        } catch (ExportException ex) {

            throw new RuntimeException("Export error: " + ex, ex);

        }

        // return proxy to caller.
        return new ClientBuffer<E>(proxy);

    }

    @SuppressWarnings("unchecked")
    public <E> E getProxy(final E obj, final boolean enableDGC) {

        try {

            return (E) getExporter(enableDGC).export((Remote) obj);
            
        } catch (ExportException e) {
            
            throw new RuntimeException(e);
            
        }
        
    }
    
    /**
     * Invokes {@link #serviceJoin(com.bigdata.service.IService, UUID, String)}
     */
    public void serviceAdded(ServiceDiscoveryEvent e) {
        
        final ServiceItem serviceItem = e.getPostEventServiceItem();

        if (serviceItem.service instanceof IService) {

//            System.err.println("serviceAdded: "+serviceItem);
            
            final UUID serviceUUID = JiniUtil.serviceID2UUID(serviceItem.serviceID);

            serviceJoin((IService) serviceItem.service, serviceUUID);

        } else {
            
            log.warn("Not an " + IService.class);
            
        }
        
    }

    /** NOP. */
    public void serviceChanged(ServiceDiscoveryEvent e) {
        
    }

    /**
     * Invokes {@link #serviceLeave(UUID)}
     */
    public void serviceRemoved(ServiceDiscoveryEvent e) {
        
        final ServiceItem serviceItem = e.getPreEventServiceItem();

        final UUID serviceUUID = JiniUtil.serviceID2UUID(serviceItem.serviceID);

//        System.err.println("serviceRemoved: " + serviceUUID);

        serviceLeave(serviceUUID);
        
    }

    /**
     * Lock controlling access to the {@link #discoveryEvent} {@link Condition}.
     */
    protected final ReentrantLock discoveryEventLock = new ReentrantLock();

    /**
     * Condition signaled any time there is a {@link DiscoveryEvent} delivered to
     * our {@link DiscoveryListener}.
     */
    protected final Condition discoveryEvent = discoveryEventLock
            .newCondition();

    /**
     * Signals anyone waiting on {@link #discoveryEvent}.
     */
    public void discarded(final DiscoveryEvent e) {

        try {
            
            discoveryEventLock.lockInterruptibly();
            
            try {
                
                discoveryEvent.signalAll();
            
            } finally {
                
                discoveryEventLock.unlock();
                
            }
            
        } catch (InterruptedException ex) {
            
            return;
            
        }
        
    }

    /**
     * Signals anyone waiting on {@link #discoveryEvent}.
     */
    public void discovered(final DiscoveryEvent e) {

        try {

            discoveryEventLock.lockInterruptibly();

            try {

                discoveryEvent.signalAll();

            } finally {

                discoveryEventLock.unlock();

            }

        } catch (InterruptedException ex) {

            return;

        }
        
    }

    /**
     * Lock controlling access to the {@link #zookeeperEvent} {@link Condition}.
     */
    protected final ReentrantLock zookeeperEventLock = new ReentrantLock();

    /**
     * Condition signaled any time there is a {@link WatchedEvent} delivered to
     * our {@link #process(WatchedEvent)}.
     */
    protected final Condition zookeeperEvent = zookeeperEventLock
            .newCondition();

    /**
     * Signals anyone awaiting {@link #zookeeperEvent}.   
     */
    public void process(final WatchedEvent event) {

        if (INFO)
            log.info(event.toString());

        try {
            
            zookeeperEventLock.lockInterruptibly();
            
            try {
                
                zookeeperEvent.signalAll();
            
            } finally {
                
                zookeeperEventLock.unlock();
                
            }
            
        } catch (InterruptedException ex) {
            
            return;
            
        }
        
        for (Watcher w : watchers) {

            w.process(event);

        }
        
    }
    
    /**
     * Adds a {@link Watcher} which will receive {@link WatchedEvent}s until it
     * is removed.
     * 
     * @param w
     *            The watcher.
     */
    public void addWatcher(final Watcher w) {

        if (w == null)
            throw new IllegalArgumentException();

        if(INFO)
            log.info("watcher="+w);
        
        watchers.add(w);

    }

    /**
     * Remove a {@link Watcher}.
     * 
     * @param w
     *            The watcher.
     */
    public void removeWatcher(final Watcher w) {

        if (w == null)
            throw new IllegalArgumentException();

        if(INFO)
            log.info("watcher="+w);

        watchers.remove(w);

    }

    private final CopyOnWriteArrayList<Watcher> watchers = new CopyOnWriteArrayList<Watcher>();
    
    /**
     * Submits the task for execution and monitors its {@link Future}.
     * 
     * @param task
     *            The task.
     * 
     * @return The {@link Future}.
     * 
     * @todo move this up to {@link AbstractFederation}?
     */
    public Future submitMonitoredTask(Callable task) {
        
        if (task == null)
            throw new IllegalArgumentException();
        
        assertOpen();
        
        final Future f = getExecutorService().submit(task);
        
        futures.add(new TaskFuture(task,f));
        
        return f;
        
    }
    
    /**
     * Glue object.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    static private class TaskFuture {

        final Callable task;

        final Future future;

        public TaskFuture(final Callable task, final Future future) {

            this.task = task;
            this.future = future;
            
        }
    
    }
    
    /**
     * A queue of futures that are monitored by a scheduled task.
     */
    final private ConcurrentLinkedQueue<TaskFuture> futures = new ConcurrentLinkedQueue<TaskFuture>();

    /**
     * Run as a scheduled task that monitors futures.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    private static class MonitorFuturesTask implements Runnable {

        protected static final Logger log = Logger.getLogger(MonitorFuturesTask.class);
        
        protected static final boolean INFO = log.isInfoEnabled();
        
        protected static final boolean DEBUG = log.isDebugEnabled();
        
        private final ConcurrentLinkedQueue<TaskFuture> futures;
        
        public MonitorFuturesTask(final ConcurrentLinkedQueue<TaskFuture> futures) {

            if (futures == null)
                throw new IllegalArgumentException();
            
            this.futures = futures;
            
        }
        
        /**
         * Removes any futures that are done and logs errors if any have failed.
         */
        public void run() {
            
            if (DEBUG)
                log.debug("#tasks=" + futures.size());
            
            for(TaskFuture f : futures) {
                
                if(f.future.isDone()) {
                 
                    futures.remove(f);
                    
                    try {
                        
                        // test the future for errors.
                        f.future.get();
                        
                    } catch (InterruptedException e) {
                        
                        /*
                         * Note: a cancelled task will throw an
                         * InterruptedException.
                         */
                        
                        if (INFO)
                            log.info(f.task.toString(), e);
                        else
                            log.warn("Interrupted: task="
                                    + f.task.getClass().getName());

                    } catch (ExecutionException e) {
                    
                        log.error(f.task.toString(), e);
                        
                    }
                    
                }
                
            }
            
        }
        
    }

}

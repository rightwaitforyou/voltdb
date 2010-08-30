/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.voltdb.fault;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.voltdb.VoltDB;
import org.voltdb.fault.VoltFault.FaultType;
import org.voltdb.logging.VoltLogger;

/**
 * FaultDistributor routes VoltFaults from reporters to entities that have
 * registered their interest in particular types/subclasses of VoltFault.
 */
public class FaultDistributor implements FaultDistributorInterface, Runnable
{
    private static final VoltLogger hostLog = new VoltLogger("HOST");

    // A list of registered handlers for each fault type.
    private HashMap<FaultType, TreeMap<Integer, List<FaultHandlerData>>> m_faultHandlers;

    // A set of unhandled faults by fault handler
    private HashMap<FaultHandler, FaultHandlerData> m_faultHandlersData = new HashMap<FaultHandler, FaultHandlerData> ();

    // A list of already-seen faults by fault type
    private HashMap<FaultType, HashSet<VoltFault>> m_knownFaults = new HashMap<FaultType, HashSet<VoltFault>>();

    // A list of faults that at least one handler has not reported handled.
    private ArrayDeque<VoltFault> m_pendingFaults = new ArrayDeque<VoltFault>();

    // A list of handled (handler, fault) pairs
    private ArrayDeque<HandledFault> m_handledFaults = new ArrayDeque<HandledFault>();

    // Faults waiting to be cleared
    private ArrayDeque<VoltFault> m_pendingClearedFaults = new ArrayDeque<VoltFault>();

    // Fault distributer runs in this thread
    private Thread m_thread;

    // Pairs a fault handlers to its specific unhandled fault set
    class FaultHandlerData {

        FaultHandlerData(FaultHandler handler) { m_handler = handler; }
        FaultHandler m_handler;

        // Faults that have been passed to the handler but not handled
        HashSet<VoltFault> m_pendingFaults = new HashSet<VoltFault>();
    }

    // Pairs a fault handler to an instance of a fault
    class HandledFault {
        HandledFault(FaultHandler handler, VoltFault fault) {
            m_handler = handler;
            m_fault = fault;
        }

        FaultHandler m_handler;
        VoltFault m_fault;
    }

    public FaultDistributor()
    {
        m_faultHandlers =
            new HashMap<FaultType, TreeMap<Integer, List<FaultHandlerData>>>();
        for (VoltFault.FaultType type : VoltFault.FaultType.values()) {
            m_knownFaults.put( type, new HashSet<VoltFault>());
        }
        m_thread = new Thread(this, "Fault Distributor");
        m_thread.setDaemon(true);
        m_thread.start();
    }

    /**
     * Register a FaultHandler object to be notified when FaultType type occurs.
     *
     * @param type The FaultType in which the caller is interested
     * @param handler The FaultHandler object which the caller wants called back
     *        when the the specified type occurs
     * @param order Where in the calling sequence of fault handlers this
     *        handler should appear.  Lower values will be called first; multiple
     *        handlers can have the same value but there is no guarantee of
     *        the order in which they will be called
     */
    public synchronized void registerFaultHandler(FaultType type,
                                                  FaultHandler handler,
                                                  int order)
    {
        TreeMap<Integer, List<FaultHandlerData>> handler_map =
            m_faultHandlers.get(type);
        List<FaultHandlerData> handler_list = null;
        if (handler_map == null)
        {
            handler_map = new TreeMap<Integer, List<FaultHandlerData>>();
            m_faultHandlers.put(type, handler_map);
            handler_list = new ArrayList<FaultHandlerData>();
            handler_map.put(order, handler_list);
        }
        else
        {
            handler_list = handler_map.get(order);
            if (handler_list == null)
            {
                handler_list = new ArrayList<FaultHandlerData>();
                handler_map.put(order, handler_list);
            }
        }
        FaultHandlerData data = new FaultHandlerData(handler);
        handler_list.add(data);
        m_faultHandlersData.put(handler, data);
    }

    /**
     * A convenience method for registering a default handler.  Maybe this gets
     * blown up in the future --izzy
     * @param handler
     */
    public void registerDefaultHandler(FaultHandler handler)
    {
        // semi-arbitrarily large enough priority value so that
        // the default handler is last
        registerFaultHandler(FaultType.UNKNOWN, handler, 1000);
    }

    /**
     * Report that a fault (represented by the fault arg) has occurred.  All
     * registered FaultHandlers for that type will get called, sequenced from
     * lowest order to highest order, with no guaranteed order within an 'order'
     * (yes, horrible word overloading).  Any reported
     * fault for which there is no registered handler will be handled by
     * any registered handlers for the UNKNOWN fault type.  If there is no
     * registered handler for the UNKNOWN fault type, a DefaultFaultHandler will
     * be installed and called, which has the end result of
     * calling VoltDB.crashVoltDB().
     *
     * @param fault The fault which is being reported
     */
    @Override
    // XXX-FAILURE need more error checking, default handling, and whatnot
    public synchronized void reportFault(VoltFault fault)
    {
        m_pendingFaults.offer(fault);
        this.notify();
    }

    /**
     * Report that the fault condition has been cleared
     */
    @Override
    public synchronized void reportFaultCleared(VoltFault fault) {
        m_pendingClearedFaults.offer(fault);
        this.notify();
    }

    @Override
    public synchronized void reportFaultHandled(FaultHandler handler, VoltFault fault)
    {
        m_handledFaults.offer(new HandledFault(handler, fault));
        this.notify();
    }

    @Override
    public void shutDown() throws InterruptedException
    {
        m_thread.interrupt();
        m_thread.join();
    }


    /*
     * Process notifications of faults that have been handled by their handlers. This removes
     * the fault from the set of outstanding faults for that handler.
     */
    private void processHandledFaults() {
        ArrayDeque<HandledFault> handledFaults;
        synchronized (this) {
            if (m_handledFaults.isEmpty()) {
                return;
            }
            handledFaults = m_handledFaults;
            m_handledFaults = new ArrayDeque<HandledFault>();
        }
        while (!handledFaults.isEmpty()) {
            HandledFault hf = handledFaults.poll();
            if (!m_faultHandlersData.containsKey(hf.m_handler)) {
                hostLog.fatal("A handled fault was reported for a handler that is not registered");
                VoltDB.crashVoltDB();
            }
            boolean removed = m_faultHandlersData.get(hf.m_handler).m_pendingFaults.remove(hf.m_fault);
            if (!removed) {
                hostLog.fatal("A handled fault was reported that was not pending for the provided handler");
                VoltDB.crashVoltDB();
            }
        }
    }

    /*
     * Check if this fault is a duplicate of a previously reported fault
     */
    private boolean duplicateCheck(VoltFault fault) {
        return !m_knownFaults.get(fault.getFaultType()).add(fault);
    }

    /*
     * Dedupe incoming fault reports and then report the new fault along with outstanding faults
     * to any interested fault handlers.
     */
    private void processPendingFaults() {
        ArrayDeque<VoltFault> pendingFaults;
        synchronized (this) {
            if (m_pendingFaults.isEmpty()) {
                return;
            }
            pendingFaults = m_pendingFaults;
            m_pendingFaults = new ArrayDeque<VoltFault>();
        }

        HashMap<FaultType, HashSet<VoltFault>> faultsMap  = new HashMap<FaultType, HashSet<VoltFault>>();
        while (!pendingFaults.isEmpty()) {
            VoltFault fault = pendingFaults.poll();
            if (duplicateCheck(fault)) {
                continue;
            }
            HashSet<VoltFault> faults = faultsMap.get(fault.getFaultType());
            if (faults == null) {
                faults = new HashSet<VoltFault>();
                faultsMap.put(fault.getFaultType(), faults);
            }
            boolean added = faults.add(fault);
            assert(added);
        }

        if (faultsMap.isEmpty()) {
            return;
        }

        for (Map.Entry<FaultType, HashSet<VoltFault>> entry : faultsMap.entrySet()) {
            TreeMap<Integer, List<FaultHandlerData>> handler_map =
                m_faultHandlers.get(entry.getKey());
            if (handler_map == null)
            {
                handler_map = m_faultHandlers.get(FaultType.UNKNOWN);
                if (handler_map == null)
                {
                    registerDefaultHandler(new DefaultFaultHandler());
                    handler_map = m_faultHandlers.get(FaultType.UNKNOWN);
                }
            }
            for (List<FaultHandlerData> handler_list : handler_map.values())
            {
                for (FaultHandlerData handlerData : handler_list)
                {
                    if (handlerData.m_pendingFaults.addAll(entry.getValue())) {
                        handlerData.m_handler.faultOccured(handlerData.m_pendingFaults);
                    }
                }
            }
        }
    }

    private void processClearedFaults() {
        ArrayDeque<VoltFault> pendingClearedFaults;
        synchronized (this) {
            if (m_pendingClearedFaults.isEmpty()) {
                return;
            }
            pendingClearedFaults = m_pendingClearedFaults;
            m_pendingClearedFaults = new ArrayDeque<VoltFault>();
        }

        HashMap<FaultType, HashSet<VoltFault>> faultsMap  = new HashMap<FaultType, HashSet<VoltFault>>();
        while (!pendingClearedFaults.isEmpty()) {
            VoltFault fault = pendingClearedFaults.poll();
            HashSet<VoltFault> faults = faultsMap.get(fault.getFaultType());
            if (faults == null) {
                faults = new HashSet<VoltFault>();
                faultsMap.put(fault.getFaultType(), faults);
            }
            boolean added = faults.add(fault);
            m_knownFaults.get(fault.getFaultType()).remove(fault);
            assert(added);
        }

        for (Map.Entry<FaultType, HashSet<VoltFault>> entry : faultsMap.entrySet()) {
            TreeMap<Integer, List<FaultHandlerData>> handler_map =
                m_faultHandlers.get(entry.getKey());
            if (handler_map == null)
            {
                handler_map = m_faultHandlers.get(FaultType.UNKNOWN);
                if (handler_map == null)
                {
                    registerDefaultHandler(new DefaultFaultHandler());
                    handler_map = m_faultHandlers.get(FaultType.UNKNOWN);
                }
            }
            for (List<FaultHandlerData> handler_list : handler_map.values())
            {
                for (FaultHandlerData handlerData : handler_list)
                {
                    handlerData.m_handler.faultCleared(entry.getValue());
                }
            }
        }
    }

    @Override
    public void run() {
        try {
            while (true) {
                processHandledFaults();
                processPendingFaults();
                processClearedFaults();
                synchronized (this) {
                    if (m_pendingFaults.isEmpty() && m_handledFaults.isEmpty() && m_pendingClearedFaults.isEmpty()) {
                        try {
                            this.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            hostLog.fatal("", e);
            VoltDB.crashVoltDB();
        }
    }
}

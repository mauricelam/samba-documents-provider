/*
 * Copyright 2021 Google Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.google.android.sambadocumentsprovider.mount

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Helper class to scan for mDNS results
 */
object MdnsManager {

    private const val TAG = "MdnsManager"

    fun discover(context: Context, serviceName: String): Flow<List<NsdServiceInfo>> {
        Log.d(
            TAG,
            "discover() called with: context = $context, serviceName = $serviceName"
        )
        val internalSet = LinkedHashSet<NsdServiceInfo>()
        val nsdManager = context.getSystemService(NsdManager::class.java)
        return callbackFlow {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    cancel("onStartDiscoveryFailed. serviceType = $serviceType, errorCode = $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    cancel("onStopDiscoveryFailed. serviceType = $serviceType, errorCode = $errorCode")
                }

                override fun onDiscoveryStarted(serviceType: String?) {
                    Log.d(
                        TAG,
                        "onDiscoveryStarted() called with: serviceType = $serviceType"
                    )
                }

                override fun onDiscoveryStopped(serviceType: String?) {
                    Log.d(
                        TAG,
                        "onDiscoveryStopped() called with: serviceType = $serviceType"
                    )
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    Log.d(TAG, "onServiceFound() called with: serviceInfo = $serviceInfo")
                    serviceInfo ?: return
                    launch {
                        try {
                            internalSet.add(resolveService(serviceInfo, nsdManager))
                            trySend(internalSet.toList())
                        } catch (e: RuntimeException) {
                            Log.w(TAG, "Unable to resolve service Info")
                        }
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                    Log.d(TAG, "onServiceLost() called with: serviceInfo = $serviceInfo")
                    internalSet.remove(serviceInfo)
                    trySend(internalSet.toList())
                }

            }
            nsdManager.discoverServices(serviceName, NsdManager.PROTOCOL_DNS_SD, listener)
            awaitClose {
                try {
                    nsdManager.stopServiceDiscovery(listener)
                } catch (e: IllegalArgumentException) {
                    Log.d(TAG, "Failed to stop discovery", e)
                }
            }
        }
    }

    private suspend fun resolveService(
        serviceInfo: NsdServiceInfo,
        nsdManager: NsdManager
    ): NsdServiceInfo {
        return suspendCoroutine {
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    it.resumeWithException(RuntimeException("Resolve failed: $errorCode"))
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                    if (serviceInfo == null) {
                        it.resumeWithException(RuntimeException("ServiceInfo cannot be null"))
                        return
                    }
                    it.resume(serviceInfo)
                }
            })
        }
    }
}
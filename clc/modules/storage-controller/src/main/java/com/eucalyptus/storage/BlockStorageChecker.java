/*************************************************************************
 * Copyright 2009-2012 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 *
 * This file may incorporate work covered under the following copyright
 * and permission notice:
 *
 *   Software License Agreement (BSD License)
 *
 *   Copyright (c) 2008, Regents of the University of California
 *   All rights reserved.
 *
 *   Redistribution and use of this software in source and binary forms,
 *   with or without modification, are permitted provided that the
 *   following conditions are met:
 *
 *     Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *     Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer
 *     in the documentation and/or other materials provided with the
 *     distribution.
 *
 *   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 *   "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 *   LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 *   FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 *   COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 *   INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 *   BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *   LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 *   CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 *   LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 *   ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 *   POSSIBILITY OF SUCH DAMAGE. USERS OF THIS SOFTWARE ACKNOWLEDGE
 *   THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE LICENSED MATERIAL,
 *   COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS SOFTWARE,
 *   AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 *   IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA,
 *   SANTA BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY,
 *   WHICH IN THE REGENTS' DISCRETION MAY INCLUDE, WITHOUT LIMITATION,
 *   REPLACEMENT OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO
 *   IDENTIFIED, OR WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT
 *   NEEDED TO COMPLY WITH ANY SUCH LICENSES OR RIGHTS.
 ************************************************************************/

package com.eucalyptus.storage;

import edu.ucsb.eucalyptus.cloud.entities.SnapshotInfo;
import edu.ucsb.eucalyptus.cloud.entities.VolumeInfo;
import edu.ucsb.eucalyptus.cloud.ws.BlockStorage;
import edu.ucsb.eucalyptus.cloud.ws.HttpWriter;
import edu.ucsb.eucalyptus.cloud.ws.SnapshotProgressCallback;

import com.eucalyptus.entities.EntityWrapper;
import com.eucalyptus.util.EucalyptusCloudException;
import com.eucalyptus.util.StorageProperties;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.log4j.Logger;


import java.io.File;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BlockStorageChecker {
	private static Logger LOG = Logger.getLogger(BlockStorageChecker.class);
	private LogicalStorageManager blockManager;
	private static boolean transferredPending = false;

	public BlockStorageChecker(LogicalStorageManager blockManager) {
		this.blockManager = blockManager;
	}

	public void startupChecks() {
		new StartupChecker().start();
	}

	private class StartupChecker extends Thread {
		public StartupChecker() {}

		public void run() {
			try {
				cleanup();
			} catch (EucalyptusCloudException e) {
				LOG.error("Startup cleanup failed", e);
			}
			blockManager.startupChecks();
		}
	}

	public void cleanup() throws EucalyptusCloudException {
		cleanVolumes();
		cleanSnapshots();
	}

	public void cleanVolumes() {
		cleanStuckVolumes();
		cleanFailedVolumes();
	}

	public void cleanSnapshots() {
		cleanStuckSnapshots();
		cleanFailedSnapshots();
	}

	public void cleanStuckVolumes() {
		EntityWrapper<VolumeInfo> db = StorageProperties.getEntityWrapper();
		VolumeInfo volumeInfo = new VolumeInfo();
		volumeInfo.setStatus(StorageProperties.Status.creating.toString());
		List<VolumeInfo> volumeInfos = db.query(volumeInfo);
		for(VolumeInfo volInfo : volumeInfos) {
			String volumeId = volInfo.getVolumeId();
			LOG.info("Cleaning failed volume " + volumeId);
			blockManager.cleanVolume(volumeId);
			db.delete(volInfo);
		}
		db.commit();
	}

	public void cleanFailedVolumes() {
		EntityWrapper<VolumeInfo> db = StorageProperties.getEntityWrapper();
		VolumeInfo volumeInfo = new VolumeInfo();
		volumeInfo.setStatus(StorageProperties.Status.failed.toString());
		List<VolumeInfo> volumeInfos = db.query(volumeInfo);
		for(VolumeInfo volInfo : volumeInfos) {
			String volumeId = volInfo.getVolumeId();
			LOG.info("Cleaning failed volume " + volumeId);
			blockManager.cleanVolume(volumeId);
			db.delete(volInfo);
		}
		db.commit();
	}

	public void cleanFailedVolume(String volumeId) {
		EntityWrapper<VolumeInfo> db = StorageProperties.getEntityWrapper();
		VolumeInfo volumeInfo = new VolumeInfo(volumeId);
		List<VolumeInfo> volumeInfos = db.query(volumeInfo);
		if(volumeInfos.size() > 0) {
			VolumeInfo volInfo = volumeInfos.get(0);
			LOG.info("Cleaning failed volume " + volumeId);
			blockManager.cleanVolume(volumeId);
			db.delete(volInfo);
		}
		db.commit();
	}

	public void cleanStuckSnapshots() {
		EntityWrapper<SnapshotInfo> db = StorageProperties.getEntityWrapper();
		SnapshotInfo snapshotInfo = new SnapshotInfo();
		snapshotInfo.setStatus(StorageProperties.Status.creating.toString());
		List<SnapshotInfo> snapshotInfos = db.query(snapshotInfo);
		for(SnapshotInfo snapInfo : snapshotInfos) {
			String snapshotId = snapInfo.getSnapshotId();
			LOG.info("Cleaning failed snapshot " + snapshotId);
			blockManager.cleanSnapshot(snapshotId);
			db.delete(snapInfo);
		}
		db.commit();
	}

	public void cleanFailedSnapshots() {
		EntityWrapper<SnapshotInfo> db = StorageProperties.getEntityWrapper();
		SnapshotInfo snapshotInfo = new SnapshotInfo();
		snapshotInfo.setStatus(StorageProperties.Status.failed.toString());
		List<SnapshotInfo> snapshotInfos = db.query(snapshotInfo);
		for(SnapshotInfo snapInfo : snapshotInfos) {
			String snapshotId = snapInfo.getSnapshotId();
			LOG.info("Cleaning failed snapshot " + snapshotId);
			blockManager.cleanSnapshot(snapshotId);
			db.delete(snapInfo);
		}
		db.commit();
	}

	public void cleanFailedSnapshot(String snapshotId) {
		EntityWrapper<SnapshotInfo> db = StorageProperties.getEntityWrapper();
		SnapshotInfo snapshotInfo = new SnapshotInfo(snapshotId);
		List<SnapshotInfo> snapshotInfos = db.query(snapshotInfo);
		if(snapshotInfos.size() > 0) {
			SnapshotInfo snapInfo = snapshotInfos.get(0);
			LOG.info("Cleaning failed snapshot " + snapshotId);
			blockManager.cleanSnapshot(snapshotId);
			db.delete(snapInfo);
		}
		db.commit();
	}

	public void transferPendingSnapshots() throws EucalyptusCloudException {
		if(!transferredPending) {
			SnapshotTransfer transferrer = new SnapshotTransfer(this);
			transferrer.start();
			transferredPending = true;
		}
	}

	public static void checkWalrusConnection() {
		HttpClient httpClient = new HttpClient();
		GetMethod getMethod = null;
		try {
			java.net.URI addrUri = new URL(StorageProperties.WALRUS_URL).toURI();
			String addrPath = addrUri.getPath();
			String addr = StorageProperties.WALRUS_URL.replaceAll(addrPath, "");
			getMethod = new GetMethod(addr);

			httpClient.executeMethod(getMethod);
			StorageProperties.enableSnapshots = true;
		} catch(Exception ex) {
			LOG.error("Could not connect to Walrus. Snapshot functionality disabled. Please check the Walrus url.");
			StorageProperties.enableSnapshots = false;
		} finally {
			if(getMethod != null)
				getMethod.releaseConnection();
		}
	}

	private class SnapshotTransfer extends Thread {
		private BlockStorageChecker checker;
		public SnapshotTransfer(final BlockStorageChecker checker) {
			this.checker = checker;
		}

		public void run() {
			EntityWrapper<SnapshotInfo> db = StorageProperties.getEntityWrapper();
			SnapshotInfo snapshotInfo = new SnapshotInfo();
			snapshotInfo.setShouldTransfer(true);
			List<SnapshotInfo> snapshotInfos = db.query(snapshotInfo);
			if(snapshotInfos.size() > 0) {
				SnapshotInfo snapInfo = snapshotInfos.get(0);
				String snapshotId = snapInfo.getSnapshotId();
				List<String> returnValues;
				try {
					returnValues = blockManager.prepareForTransfer(snapshotId);
				} catch (EucalyptusCloudException e) {
					db.rollback();
					LOG.error(e);
					return;
				}
				if(returnValues.size() > 0) {
					String snapshotFileName = returnValues.get(0);
					File snapshotFile = new File(snapshotFileName);
					Map<String, String> httpParamaters = new HashMap<String, String>();
					HttpWriter httpWriter;
					SnapshotProgressCallback callback = new SnapshotProgressCallback(snapshotId, snapshotFile.length(), StorageProperties.TRANSFER_CHUNK_SIZE);
					httpWriter = new HttpWriter("PUT", snapshotFile, String.valueOf(snapshotFile.length()), callback, "snapset-" + UUID.randomUUID(), snapshotId, "StoreSnapshot", null, httpParamaters);
					try {
						httpWriter.run();
					} catch(Exception ex) {
						db.rollback();
						LOG.error(ex, ex);
						checker.cleanFailedSnapshot(snapshotId);
					}
				}
			}
			db.commit();	
		}
	}
}

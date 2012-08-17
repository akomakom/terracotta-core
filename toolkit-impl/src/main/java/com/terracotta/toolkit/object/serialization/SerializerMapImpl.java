/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.object.serialization;

import com.tc.net.GroupID;
import com.tc.object.LiteralValues;
import com.tc.object.SerializationUtil;
import com.tc.object.TCObject;
import com.tc.object.bytecode.Manageable;
import com.tc.object.bytecode.ManagerUtil;
import com.tc.object.locks.LockLevel;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class SerializerMapImpl<K, V> implements SerializerMap<K, V>, Manageable {

  private final Map<K, V>   localMap = new HashMap<K, V>();
  private volatile Object   localResolveLock;
  private volatile TCObject tcObject;
  private volatile GroupID  gid;
  private volatile String   lockID;

  @Override
  public void __tc_managed(TCObject t) {
    this.tcObject = t;
    this.gid = new GroupID(t.getObjectID().getGroupID());
    this.localResolveLock = tcObject.getResolveLock();
  }

  @Override
  public TCObject __tc_managed() {
    return tcObject;
  }

  @Override
  public boolean __tc_isManaged() {
    return tcObject != null;
  }

  private void writeLock() {
    lock(LockLevel.WRITE_LEVEL);
  }

  private void writeUnlock() {
    unlock(LockLevel.WRITE_LEVEL);
  }

  private void readLock() {
    lock(LockLevel.READ_LEVEL);
  }

  private void readUnlock() {
    unlock(LockLevel.READ_LEVEL);
  }

  private void lock(int lockLevel) {
    ManagerUtil.beginLock(getLockID(), lockLevel);
  }

  private void unlock(int lockLevel) {
    ManagerUtil.commitLock(getLockID(), lockLevel);
  }

  private String getLockID() {
    if (lockID != null) { return lockID; }

    lockID = "__tc_serializer_map_" + tcObject.getObjectID().toLong();
    return lockID;
  }

  @Override
  public V put(K key, V value) {
      writeLock();
      try {
        V val = createSCOIfNeeded(value);
        synchronized (localResolveLock) {
        V ret = internalput(key, val);
          tcObject.logicalInvoke(SerializationUtil.PUT, SerializationUtil.PUT_SIGNATURE, new Object[] { key, val });
        return ret;
        }
      } finally {
        writeUnlock();
      }
  }

  @Override
  public V get(K key) {
      readLock();
      try {
        synchronized (localResolveLock) {
          return localMap.get(key);
        }
      } finally {
        readUnlock();
      }
  }

  @Override
  public V localGet(K key) {
    synchronized (localResolveLock) {
      return localMap.get(key);
    }
  }

  private V createSCOIfNeeded(V value) {
    if (LiteralValues.isLiteralInstance(value)) { return value; }
    SerializedClusterObject sco = new SerializedClusterObjectImpl(null, (byte[]) value);
    ManagerUtil.lookupOrCreate(sco, gid);
    return (V) sco;
  }

  protected V internalput(K key, V value) {
    if (LiteralValues.isLiteralInstance(value)) { return localMap.put(key, value); }
    return localMap.put(key, (V) ((SerializedClusterObject) value).getBytes());
  }

  protected Map<K, V> internalGetMap() {
    return Collections.unmodifiableMap(localMap);
  }

}
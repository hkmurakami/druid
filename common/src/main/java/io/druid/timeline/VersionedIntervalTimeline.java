/*
 * Druid - a distributed column store.
 * Copyright (C) 2012, 2013  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package io.druid.timeline;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.metamx.common.guava.Comparators;
import com.metamx.common.logger.Logger;
import io.druid.timeline.partition.ImmutablePartitionHolder;
import io.druid.timeline.partition.PartitionChunk;
import io.druid.timeline.partition.PartitionHolder;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * VersionedIntervalTimeline is a data structure that manages objects on a specific timeline.
 * <p/>
 * It associates a jodatime Interval and a generically-typed version with the object that is being stored.
 * <p/>
 * In the event of overlapping timeline entries, timeline intervals may be chunked. The underlying data associated
 * with a timeline entry remains unchanged when chunking occurs.
 * <p/>
 * After loading objects via the add() method, the lookup(Interval) method can be used to get the list of the most
 * recent objects (according to the version) that match the given interval.  The intent is that objects represent
 * a certain time period and when you do a lookup(), you are asking for all of the objects that you need to look
 * at in order to get a correct answer about that time period.
 * <p/>
 * The findOvershadowed() method returns a list of objects that will never be returned by a call to lookup() because
 * they are overshadowed by some other object.  This can be used in conjunction with the add() and remove() methods
 * to achieve "atomic" updates.  First add new items, then check if those items caused anything to be overshadowed, if
 * so, remove the overshadowed elements and you have effectively updated your data set without any user impact.
 */
public class VersionedIntervalTimeline<VersionType, ObjectType>
{
  private static final Logger log = new Logger(VersionedIntervalTimeline.class);

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

  final NavigableMap<Interval, TimelineEntry> completePartitionsTimeline = new TreeMap<Interval, TimelineEntry>(
      Comparators.intervalsByStartThenEnd()
  );
  final NavigableMap<Interval, TimelineEntry> incompletePartitionsTimeline = new TreeMap<Interval, TimelineEntry>(
      Comparators.intervalsByStartThenEnd()
  );
  private final Map<Interval, TreeMap<VersionType, TimelineEntry>> allTimelineEntries = Maps.newHashMap();

  private final Comparator<? super VersionType> versionComparator;

  public VersionedIntervalTimeline(
      Comparator<? super VersionType> versionComparator
  )
  {
    this.versionComparator = versionComparator;
  }

  public void add(final Interval interval, VersionType version, PartitionChunk<ObjectType> object)
  {
    try {
      lock.writeLock().lock();

      Map<VersionType, TimelineEntry> exists = allTimelineEntries.get(interval);
      TimelineEntry entry = null;

      if (exists == null) {
        entry = new TimelineEntry(interval, version, new PartitionHolder<ObjectType>(object));
        TreeMap<VersionType, TimelineEntry> versionEntry = new TreeMap<VersionType, TimelineEntry>(versionComparator);
        versionEntry.put(version, entry);
        allTimelineEntries.put(interval, versionEntry);
      } else {
        entry = exists.get(version);

        if (entry == null) {
          entry = new TimelineEntry(interval, version, new PartitionHolder<ObjectType>(object));
          exists.put(version, entry);
        } else {
          PartitionHolder<ObjectType> partitionHolder = entry.getPartitionHolder();
          partitionHolder.add(object);
        }
      }

      if (entry.getPartitionHolder().isComplete()) {
        add(completePartitionsTimeline, interval, entry);
      }

      add(incompletePartitionsTimeline, interval, entry);
    }
    finally {
      lock.writeLock().unlock();
    }
  }

  public PartitionChunk<ObjectType> remove(Interval interval, VersionType version, PartitionChunk<ObjectType> chunk)
  {
    try {
      lock.writeLock().lock();

      Map<VersionType, TimelineEntry> versionEntries = allTimelineEntries.get(interval);
      if (versionEntries == null) {
        return null;
      }

      TimelineEntry entry = versionEntries.get(version);
      if (entry == null) {
        return null;
      }

      PartitionChunk<ObjectType> retVal = entry.getPartitionHolder().remove(chunk);
      if (entry.getPartitionHolder().isEmpty()) {
        versionEntries.remove(version);
        if (versionEntries.isEmpty()) {
          allTimelineEntries.remove(interval);
        }

        remove(incompletePartitionsTimeline, interval, entry, true);
      }

      remove(completePartitionsTimeline, interval, entry, false);

      return retVal;
    }
    finally {
      lock.writeLock().unlock();
    }
  }

  public PartitionHolder<ObjectType> findEntry(Interval interval, VersionType version)
  {
    try {
      lock.readLock().lock();
      for (Map.Entry<Interval, TreeMap<VersionType, TimelineEntry>> entry : allTimelineEntries.entrySet()) {
        if (entry.getKey().equals(interval) || entry.getKey().contains(interval)) {
          TimelineEntry foundEntry = entry.getValue().get(version);
          if (foundEntry != null) {
            return new ImmutablePartitionHolder<ObjectType>(
                foundEntry.getPartitionHolder()
            );
          }
        }
      }

      return null;
    }
    finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Does a lookup for the objects representing the given time interval.  Will *only* return
   * PartitionHolders that are complete.
   *
   * @param interval interval to find objects for
   *
   * @return Holders representing the interval that the objects exist for, PartitionHolders
   *         are guaranteed to be complete
   */
  public List<TimelineObjectHolder<VersionType, ObjectType>> lookup(Interval interval)
  {
    try {
      lock.readLock().lock();
      return lookup(interval, false);
    }
    finally {
      lock.readLock().unlock();
    }
  }

  public List<TimelineObjectHolder<VersionType, ObjectType>> findOvershadowed()
  {
    try {
      lock.readLock().lock();
      List<TimelineObjectHolder<VersionType, ObjectType>> retVal = new ArrayList<TimelineObjectHolder<VersionType, ObjectType>>();

      Map<Interval, Map<VersionType, TimelineEntry>> overShadowed = Maps.newHashMap();
      for (Map.Entry<Interval, TreeMap<VersionType, TimelineEntry>> versionEntry : allTimelineEntries.entrySet()) {
        Map<VersionType, TimelineEntry> versionCopy = Maps.newHashMap();
        versionCopy.putAll(versionEntry.getValue());
        overShadowed.put(versionEntry.getKey(), versionCopy);
      }

      for (Map.Entry<Interval, TimelineEntry> entry : completePartitionsTimeline.entrySet()) {
        Map<VersionType, TimelineEntry> versionEntry = overShadowed.get(entry.getValue().getTrueInterval());
        if (versionEntry != null) {
          versionEntry.remove(entry.getValue().getVersion());
          if (versionEntry.isEmpty()) {
            overShadowed.remove(entry.getValue().getTrueInterval());
          }
        }
      }

      for (Map.Entry<Interval, TimelineEntry> entry : incompletePartitionsTimeline.entrySet()) {
        Map<VersionType, TimelineEntry> versionEntry = overShadowed.get(entry.getValue().getTrueInterval());
        if (versionEntry != null) {
          versionEntry.remove(entry.getValue().getVersion());
          if (versionEntry.isEmpty()) {
            overShadowed.remove(entry.getValue().getTrueInterval());
          }
        }
      }

      for (Map.Entry<Interval, Map<VersionType, TimelineEntry>> versionEntry : overShadowed.entrySet()) {
        for (Map.Entry<VersionType, TimelineEntry> entry : versionEntry.getValue().entrySet()) {
          TimelineEntry object = entry.getValue();
          retVal.add(
              new TimelineObjectHolder<VersionType, ObjectType>(
                  object.getTrueInterval(),
                  object.getVersion(),
                  object.getPartitionHolder()
              )
          );
        }
      }

      return retVal;
    }
    finally {
      lock.readLock().unlock();
    }
  }

  private void add(
      NavigableMap<Interval, TimelineEntry> timeline,
      Interval interval,
      TimelineEntry entry
  )
  {
    TimelineEntry existsInTimeline = timeline.get(interval);

    if (existsInTimeline != null) {
      int compare = versionComparator.compare(entry.getVersion(), existsInTimeline.getVersion());
      if (compare > 0) {
        addIntervalToTimeline(interval, entry, timeline);
      }
      return;
    }

    Interval lowerKey = timeline.lowerKey(interval);

    if (lowerKey != null) {
      if (addAtKey(timeline, lowerKey, entry)) {
        return;
      }
    }

    Interval higherKey = timeline.higherKey(interval);

    if (higherKey != null) {
      if (addAtKey(timeline, higherKey, entry)) {
        return;
      }
    }

    addIntervalToTimeline(interval, entry, timeline);
  }

  /**
   *
   * @param timeline
   * @param key
   * @param entry
   * @return boolean flag indicating whether or not we inserted or discarded something
   */
  private boolean addAtKey(
      NavigableMap<Interval, TimelineEntry> timeline,
      Interval key,
      TimelineEntry entry
  )
  {
    boolean retVal = false;
    Interval currKey = key;
    Interval entryInterval = entry.getTrueInterval();

    if (!currKey.overlaps(entryInterval)) {
      return false;
    }

    while (entryInterval != null && currKey != null && currKey.overlaps(entryInterval)) {
      Interval nextKey = timeline.higherKey(currKey);

      int versionCompare = versionComparator.compare(
          entry.getVersion(),
          timeline.get(currKey).getVersion()
      );

      if (versionCompare < 0) {
        if (currKey.contains(entryInterval)) {
          return true;
        } else if (currKey.getStart().isBefore(entryInterval.getStart())) {
          entryInterval = new Interval(currKey.getEnd(), entryInterval.getEnd());
        } else {
          addIntervalToTimeline(new Interval(entryInterval.getStart(), currKey.getStart()), entry, timeline);

          if (entryInterval.getEnd().isAfter(currKey.getEnd())) {
            entryInterval = new Interval(currKey.getEnd(), entryInterval.getEnd());
          } else {
            entryInterval = null; // discard this entry
          }
        }
      } else if (versionCompare > 0) {
        TimelineEntry oldEntry = timeline.remove(currKey);

        if (currKey.contains(entryInterval)) {
          addIntervalToTimeline(new Interval(currKey.getStart(), entryInterval.getStart()), oldEntry, timeline);
          addIntervalToTimeline(new Interval(entryInterval.getEnd(), currKey.getEnd()), oldEntry, timeline);
          addIntervalToTimeline(entryInterval, entry, timeline);

          return true;
        } else if (currKey.getStart().isBefore(entryInterval.getStart())) {
          addIntervalToTimeline(new Interval(currKey.getStart(), entryInterval.getStart()), oldEntry, timeline);
        } else if (entryInterval.getEnd().isBefore(currKey.getEnd())) {
          addIntervalToTimeline(new Interval(entryInterval.getEnd(), currKey.getEnd()), oldEntry, timeline);
        }
      } else {
        if (timeline.get(currKey).equals(entry)) {
          // This occurs when restoring segments
          timeline.remove(currKey);
        } else {
          throw new UnsupportedOperationException(
              String.format(
                  "Cannot add overlapping segments [%s and %s] with the same version [%s]",
                  currKey,
                  entryInterval,
                  entry.getVersion()
              )
          );
        }
      }

      currKey = nextKey;
      retVal = true;
    }

    addIntervalToTimeline(entryInterval, entry, timeline);

    return retVal;
  }

  private void addIntervalToTimeline(
      Interval interval,
      TimelineEntry entry,
      NavigableMap<Interval, TimelineEntry> timeline
  )
  {
    if (interval != null && interval.toDurationMillis() > 0) {
      timeline.put(interval, entry);
    }
  }

  private void remove(
      NavigableMap<Interval, TimelineEntry> timeline,
      Interval interval,
      TimelineEntry entry,
      boolean incompleteOk
  )
  {
    List<Interval> intervalsToRemove = Lists.newArrayList();
    TimelineEntry removed = timeline.get(interval);

    if (removed == null) {
      Iterator<Map.Entry<Interval, TimelineEntry>> iter = timeline.entrySet().iterator();
      while (iter.hasNext()) {
        Map.Entry<Interval, TimelineEntry> timelineEntry = iter.next();
        if (timelineEntry.getValue() == entry) {
          intervalsToRemove.add(timelineEntry.getKey());
        }
      }
    } else {
      intervalsToRemove.add(interval);
    }

    for (Interval i : intervalsToRemove) {
      remove(timeline, i, incompleteOk);
    }
  }

  private void remove(
      NavigableMap<Interval, TimelineEntry> timeline,
      Interval interval,
      boolean incompleteOk
  )
  {
    timeline.remove(interval);

    for (Map.Entry<Interval, TreeMap<VersionType, TimelineEntry>> versionEntry : allTimelineEntries.entrySet()) {
      if (versionEntry.getKey().overlap(interval) != null) {
        TimelineEntry timelineEntry = versionEntry.getValue().lastEntry().getValue();
        if (timelineEntry.getPartitionHolder().isComplete() || incompleteOk) {
          add(timeline, versionEntry.getKey(), timelineEntry);
        }
      }
    }
  }

  private List<TimelineObjectHolder<VersionType, ObjectType>> lookup(Interval interval, boolean incompleteOk)
  {
    List<TimelineObjectHolder<VersionType, ObjectType>> retVal = new ArrayList<TimelineObjectHolder<VersionType, ObjectType>>();
    NavigableMap<Interval, TimelineEntry> timeline = (incompleteOk)
                                                     ? incompletePartitionsTimeline
                                                     : completePartitionsTimeline;

    for (Map.Entry<Interval, TimelineEntry> entry : timeline.entrySet()) {
      Interval timelineInterval = entry.getKey();
      TimelineEntry val = entry.getValue();

      if (timelineInterval.overlaps(interval)) {
        retVal.add(
            new TimelineObjectHolder<VersionType, ObjectType>(
                timelineInterval,
                val.getVersion(),
                val.getPartitionHolder()
            )
        );
      }
    }

    if (retVal.isEmpty()) {
      return retVal;
    }

    TimelineObjectHolder<VersionType, ObjectType> firstEntry = retVal.get(0);
    if (interval.overlaps(firstEntry.getInterval()) && interval.getStart()
                                                               .isAfter(firstEntry.getInterval().getStart())) {
      retVal.set(
          0,
          new TimelineObjectHolder<VersionType, ObjectType>(
              new Interval(interval.getStart(), firstEntry.getInterval().getEnd()),
              firstEntry.getVersion(),
              firstEntry.getObject()
          )
      );
    }

    TimelineObjectHolder<VersionType, ObjectType> lastEntry = retVal.get(retVal.size() - 1);
    if (interval.overlaps(lastEntry.getInterval()) && interval.getEnd().isBefore(lastEntry.getInterval().getEnd())) {
      retVal.set(
          retVal.size() - 1,
          new TimelineObjectHolder<VersionType, ObjectType>(
              new Interval(lastEntry.getInterval().getStart(), interval.getEnd()),
              lastEntry.getVersion(),
              lastEntry.getObject()
          )
      );
    }

    return retVal;
  }

  public class TimelineEntry
  {
    private final Interval trueInterval;
    private final VersionType version;
    private final PartitionHolder<ObjectType> partitionHolder;

    public TimelineEntry(Interval trueInterval, VersionType version, PartitionHolder<ObjectType> partitionHolder)
    {
      this.trueInterval = trueInterval;
      this.version = version;
      this.partitionHolder = partitionHolder;
    }

    public Interval getTrueInterval()
    {
      return trueInterval;
    }

    public VersionType getVersion()
    {
      return version;
    }

    public PartitionHolder<ObjectType> getPartitionHolder()
    {
      return partitionHolder;
    }
  }

  public static void main(String[] args)
  {
    System.out.println(new Interval(new DateTime(), (DateTime) null));
  }
}

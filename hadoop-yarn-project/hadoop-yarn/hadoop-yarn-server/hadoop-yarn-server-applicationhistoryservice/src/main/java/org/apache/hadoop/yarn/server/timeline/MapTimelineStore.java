/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.timeline;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.yarn.api.records.timeline.TimelineDomain;
import org.apache.hadoop.yarn.api.records.timeline.TimelineDomains;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEntities;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEntity;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEvent;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEvents;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEvents.EventsOfOneEntity;
import org.apache.hadoop.yarn.api.records.timeline.TimelinePutResponse;
import org.apache.hadoop.yarn.api.records.timeline.TimelinePutResponse.TimelinePutError;
import org.apache.hadoop.yarn.server.timeline.TimelineDataManager.CheckAcl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;

import static org.apache.hadoop.yarn.server.timeline.TimelineDataManager.DEFAULT_DOMAIN_ID;

/**
 * Map based implementation of {@link TimelineStore}. A hash map
 * implementation should be connected to this implementation through a
 * {@link TimelineStoreMapAdapter}.
 *
 * The methods are synchronized to avoid concurrent modifications.
 *
 */
@Private
@Unstable
abstract class MapTimelineStore
    extends AbstractService implements TimelineStore {

  protected TimelineStoreMapAdapter<EntityIdentifier, TimelineEntity> entities;
  protected TimelineStoreMapAdapter<EntityIdentifier, Long> entityInsertTimes;
  protected TimelineStoreMapAdapter<String, TimelineDomain> domainById;
  protected TimelineStoreMapAdapter<String, Set<TimelineDomain>> domainsByOwner;

  private boolean serviceStopped = false;

  private static final Log LOG
      = LogFactory.getLog(MapTimelineStore.class);

  public MapTimelineStore() {
    super(MapTimelineStore.class.getName());
  }

  public MapTimelineStore(String name) {
    super(name);
  }

  @Override
  protected synchronized void serviceStop() throws Exception {
    serviceStopped = true;
    super.serviceStop();
  }

  @Override
  public synchronized TimelineEntities getEntities(String entityType, Long limit,
      Long windowStart, Long windowEnd, String fromId, Long fromTs,
      NameValuePair primaryFilter, Collection<NameValuePair> secondaryFilters,
      EnumSet<Field> fields, CheckAcl checkAcl) throws IOException {
    if (serviceStopped) {
      LOG.info("Service stopped, return null for the storage");
      return null;
    }
    if (limit == null) {
      limit = DEFAULT_LIMIT;
    }
    if (windowStart == null) {
      windowStart = Long.MIN_VALUE;
    }
    if (windowEnd == null) {
      windowEnd = Long.MAX_VALUE;
    }
    if (fields == null) {
      fields = EnumSet.allOf(Field.class);
    }

    Iterator<TimelineEntity> entityIterator = null;
    if (fromId != null) {
      TimelineEntity firstEntity = entities.get(new EntityIdentifier(fromId,
          entityType));
      if (firstEntity == null) {
        return new TimelineEntities();
      } else {
        entityIterator = entities.valueSetIterator(firstEntity);
      }
    }
    if (entityIterator == null) {
      entityIterator = entities.valueSetIterator();
    }

    List<TimelineEntity> entitiesSelected = new ArrayList<TimelineEntity>();
    while (entityIterator.hasNext()) {
      TimelineEntity entity = entityIterator.next();
      if (entitiesSelected.size() >= limit) {
        break;
      }
      if (!entity.getEntityType().equals(entityType)) {
        continue;
      }
      if (entity.getStartTime() <= windowStart) {
        continue;
      }
      if (entity.getStartTime() > windowEnd) {
        continue;
      }
      if (fromTs != null && entityInsertTimes.get(new EntityIdentifier(
          entity.getEntityId(), entity.getEntityType())) > fromTs) {
        continue;
      }
      if (primaryFilter != null &&
          !matchPrimaryFilter(entity.getPrimaryFilters(), primaryFilter)) {
        continue;
      }
      if (secondaryFilters != null) { // AND logic
        boolean flag = true;
        for (NameValuePair secondaryFilter : secondaryFilters) {
          if (secondaryFilter != null && !matchPrimaryFilter(
              entity.getPrimaryFilters(), secondaryFilter) &&
              !matchFilter(entity.getOtherInfo(), secondaryFilter)) {
            flag = false;
            break;
          }
        }
        if (!flag) {
          continue;
        }
      }
      if (entity.getDomainId() == null) {
        entity.setDomainId(DEFAULT_DOMAIN_ID);
      }
      if (checkAcl == null || checkAcl.check(entity)) {
        entitiesSelected.add(entity);
      }
    }
    List<TimelineEntity> entitiesToReturn = new ArrayList<TimelineEntity>();
    for (TimelineEntity entitySelected : entitiesSelected) {
      entitiesToReturn.add(maskFields(entitySelected, fields));
    }
    Collections.sort(entitiesToReturn);
    TimelineEntities entitiesWrapper = new TimelineEntities();
    entitiesWrapper.setEntities(entitiesToReturn);
    return entitiesWrapper;
  }

  @Override
  public synchronized TimelineEntity getEntity(String entityId, String entityType,
      EnumSet<Field> fieldsToRetrieve) {
    if (serviceStopped) {
      LOG.info("Service stopped, return null for the storage");
      return null;
    }
    if (fieldsToRetrieve == null) {
      fieldsToRetrieve = EnumSet.allOf(Field.class);
    }
    TimelineEntity entity = entities.get(new EntityIdentifier(entityId, entityType));
    if (entity == null) {
      return null;
    } else {
      return maskFields(entity, fieldsToRetrieve);
    }
  }

  @Override
  public synchronized TimelineEvents getEntityTimelines(String entityType,
      SortedSet<String> entityIds, Long limit, Long windowStart,
      Long windowEnd,
      Set<String> eventTypes) {
    if (serviceStopped) {
      LOG.info("Service stopped, return null for the storage");
      return null;
    }
    TimelineEvents allEvents = new TimelineEvents();
    if (entityIds == null) {
      return allEvents;
    }
    if (limit == null) {
      limit = DEFAULT_LIMIT;
    }
    if (windowStart == null) {
      windowStart = Long.MIN_VALUE;
    }
    if (windowEnd == null) {
      windowEnd = Long.MAX_VALUE;
    }
    for (String entityId : entityIds) {
      EntityIdentifier entityID = new EntityIdentifier(entityId, entityType);
      TimelineEntity entity = entities.get(entityID);
      if (entity == null) {
        continue;
      }
      EventsOfOneEntity events = new EventsOfOneEntity();
      events.setEntityId(entityId);
      events.setEntityType(entityType);
      for (TimelineEvent event : entity.getEvents()) {
        if (events.getEvents().size() >= limit) {
          break;
        }
        if (event.getTimestamp() <= windowStart) {
          continue;
        }
        if (event.getTimestamp() > windowEnd) {
          continue;
        }
        if (eventTypes != null && !eventTypes.contains(event.getEventType())) {
          continue;
        }
        events.addEvent(event);
      }
      allEvents.addEvent(events);
    }
    return allEvents;
  }

  @Override
  public TimelineDomain getDomain(String domainId)
      throws IOException {
    if (serviceStopped) {
      LOG.info("Service stopped, return null for the storage");
      return null;
    }
    TimelineDomain domain = domainById.get(domainId);
    if (domain == null) {
      return null;
    } else {
      return createTimelineDomain(
          domain.getId(),
          domain.getDescription(),
          domain.getOwner(),
          domain.getReaders(),
          domain.getWriters(),
          domain.getCreatedTime(),
          domain.getModifiedTime());
    }
  }

  @Override
  public TimelineDomains getDomains(String owner)
      throws IOException {
    if (serviceStopped) {
      LOG.info("Service stopped, return null for the storage");
      return null;
    }
    List<TimelineDomain> domains = new ArrayList<TimelineDomain>();
    Set<TimelineDomain> domainsOfOneOwner = domainsByOwner.get(owner);
    if (domainsOfOneOwner == null) {
      return new TimelineDomains();
    }
    for (TimelineDomain domain : domainsByOwner.get(owner)) {
      TimelineDomain domainToReturn = createTimelineDomain(
          domain.getId(),
          domain.getDescription(),
          domain.getOwner(),
          domain.getReaders(),
          domain.getWriters(),
          domain.getCreatedTime(),
          domain.getModifiedTime());
      domains.add(domainToReturn);
    }
    Collections.sort(domains, new Comparator<TimelineDomain>() {
      @Override
      public int compare(
          TimelineDomain domain1, TimelineDomain domain2) {
         int result = domain2.getCreatedTime().compareTo(
             domain1.getCreatedTime());
         if (result == 0) {
           return domain2.getModifiedTime().compareTo(
               domain1.getModifiedTime());
         } else {
           return result;
         }
      }
    });
    TimelineDomains domainsToReturn = new TimelineDomains();
    domainsToReturn.addDomains(domains);
    return domainsToReturn;
  }

  @Override
  public synchronized TimelinePutResponse put(TimelineEntities data) {
    TimelinePutResponse response = new TimelinePutResponse();
    if (serviceStopped) {
      LOG.info("Service stopped, return null for the storage");
      TimelinePutError error = new TimelinePutError();
      error.setErrorCode(TimelinePutError.IO_EXCEPTION);
      response.addError(error);
      return response;
    }
    for (TimelineEntity entity : data.getEntities()) {
      EntityIdentifier entityId =
          new EntityIdentifier(entity.getEntityId(), entity.getEntityType());
      // store entity info in memory
      TimelineEntity existingEntity = entities.get(entityId);
      boolean needsPut = false;
      if (existingEntity == null) {
        existingEntity = new TimelineEntity();
        existingEntity.setEntityId(entity.getEntityId());
        existingEntity.setEntityType(entity.getEntityType());
        existingEntity.setStartTime(entity.getStartTime());
        if (entity.getDomainId() == null ||
            entity.getDomainId().length() == 0) {
          TimelinePutError error = new TimelinePutError();
          error.setEntityId(entityId.getId());
          error.setEntityType(entityId.getType());
          error.setErrorCode(TimelinePutError.NO_DOMAIN);
          response.addError(error);
          continue;
        }
        existingEntity.setDomainId(entity.getDomainId());
        // insert a new entity to the storage, update insert time map
        entityInsertTimes.put(entityId, System.currentTimeMillis());
        needsPut = true;
      }
      if (entity.getEvents() != null) {
        if (existingEntity.getEvents() == null) {
          existingEntity.setEvents(entity.getEvents());
        } else {
          existingEntity.addEvents(entity.getEvents());
        }
        Collections.sort(existingEntity.getEvents());
        needsPut = true;
      }
      // check startTime
      if (existingEntity.getStartTime() == null) {
        if (existingEntity.getEvents() == null
            || existingEntity.getEvents().isEmpty()) {
          TimelinePutError error = new TimelinePutError();
          error.setEntityId(entityId.getId());
          error.setEntityType(entityId.getType());
          error.setErrorCode(TimelinePutError.NO_START_TIME);
          response.addError(error);
          entities.remove(entityId);
          entityInsertTimes.remove(entityId);
          continue;
        } else {
          Long min = Long.MAX_VALUE;
          for (TimelineEvent e : entity.getEvents()) {
            if (min > e.getTimestamp()) {
              min = e.getTimestamp();
            }
          }
          existingEntity.setStartTime(min);
          needsPut = true;
        }
      }
      if (entity.getPrimaryFilters() != null) {
        if (existingEntity.getPrimaryFilters() == null) {
          existingEntity.setPrimaryFilters(new HashMap<String, Set<Object>>());
        }
        for (Entry<String, Set<Object>> pf :
            entity.getPrimaryFilters().entrySet()) {
          for (Object pfo : pf.getValue()) {
            existingEntity.addPrimaryFilter(pf.getKey(), maybeConvert(pfo));
            needsPut = true;
          }
        }
      }
      if (entity.getOtherInfo() != null) {
        if (existingEntity.getOtherInfo() == null) {
          existingEntity.setOtherInfo(new HashMap<String, Object>());
        }
        for (Entry<String, Object> info : entity.getOtherInfo().entrySet()) {
          existingEntity.addOtherInfo(info.getKey(),
              maybeConvert(info.getValue()));
          needsPut = true;
        }
      }
      if (needsPut) {
        entities.put(entityId, existingEntity);
      }

      // relate it to other entities
      if (entity.getRelatedEntities() == null) {
        continue;
      }
      for (Entry<String, Set<String>> partRelatedEntities : entity
          .getRelatedEntities().entrySet()) {
        if (partRelatedEntities == null) {
          continue;
        }
        for (String idStr : partRelatedEntities.getValue()) {
          EntityIdentifier relatedEntityId =
              new EntityIdentifier(idStr, partRelatedEntities.getKey());
          TimelineEntity relatedEntity = entities.get(relatedEntityId);
          if (relatedEntity != null) {
            if (relatedEntity.getDomainId().equals(
                existingEntity.getDomainId())) {
              relatedEntity.addRelatedEntity(
                  existingEntity.getEntityType(), existingEntity.getEntityId());
              entities.put(relatedEntityId, relatedEntity);
            } else {
              // in this case the entity will be put, but the relation will be
              // ignored
              TimelinePutError error = new TimelinePutError();
              error.setEntityType(existingEntity.getEntityType());
              error.setEntityId(existingEntity.getEntityId());
              error.setErrorCode(TimelinePutError.FORBIDDEN_RELATION);
              response.addError(error);
            }
          } else {
            relatedEntity = new TimelineEntity();
            relatedEntity.setEntityId(relatedEntityId.getId());
            relatedEntity.setEntityType(relatedEntityId.getType());
            relatedEntity.setStartTime(existingEntity.getStartTime());
            relatedEntity.addRelatedEntity(existingEntity.getEntityType(),
                existingEntity.getEntityId());
            relatedEntity.setDomainId(existingEntity.getDomainId());
            entities.put(relatedEntityId, relatedEntity);
            entityInsertTimes.put(relatedEntityId, System.currentTimeMillis());
          }
        }
      }
    }
    return response;
  }

  public void put(TimelineDomain domain) throws IOException {
    if (serviceStopped) {
      LOG.info("Service stopped, return null for the storage");
      return;
    }
    TimelineDomain domainToReplace =
        domainById.get(domain.getId());
    Long currentTimestamp = System.currentTimeMillis();
    TimelineDomain domainToStore = createTimelineDomain(
        domain.getId(), domain.getDescription(), domain.getOwner(),
        domain.getReaders(), domain.getWriters(),
        (domainToReplace == null ?
            currentTimestamp : domainToReplace.getCreatedTime()),
        currentTimestamp);
    domainById.put(domainToStore.getId(), domainToStore);
    Set<TimelineDomain> domainsByOneOwner =
        domainsByOwner.get(domainToStore.getOwner());
    if (domainsByOneOwner == null) {
      domainsByOneOwner = new HashSet<TimelineDomain>();
      domainsByOwner.put(domainToStore.getOwner(), domainsByOneOwner);
    }
    if (domainToReplace != null) {
      domainsByOneOwner.remove(domainToReplace);
    }
    domainsByOneOwner.add(domainToStore);
  }

  private static TimelineDomain createTimelineDomain(
      String id, String description, String owner,
      String readers, String writers,
      Long createdTime, Long modifiedTime) {
    TimelineDomain domainToStore = new TimelineDomain();
    domainToStore.setId(id);
    domainToStore.setDescription(description);
    domainToStore.setOwner(owner);
    domainToStore.setReaders(readers);
    domainToStore.setWriters(writers);
    domainToStore.setCreatedTime(createdTime);
    domainToStore.setModifiedTime(modifiedTime);
    return domainToStore;
  }

  private static TimelineEntity maskFields(
      TimelineEntity entity, EnumSet<Field> fields) {
    // Conceal the fields that are not going to be exposed
    TimelineEntity entityToReturn = new TimelineEntity();
    entityToReturn.setEntityId(entity.getEntityId());
    entityToReturn.setEntityType(entity.getEntityType());
    entityToReturn.setStartTime(entity.getStartTime());
    entityToReturn.setDomainId(entity.getDomainId());
    // Deep copy
    if (fields.contains(Field.EVENTS)) {
      entityToReturn.addEvents(entity.getEvents());
    } else if (fields.contains(Field.LAST_EVENT_ONLY)) {
      entityToReturn.addEvent(entity.getEvents().get(0));
    } else {
      entityToReturn.setEvents(null);
    }
    if (fields.contains(Field.RELATED_ENTITIES)) {
      entityToReturn.addRelatedEntities(entity.getRelatedEntities());
    } else {
      entityToReturn.setRelatedEntities(null);
    }
    if (fields.contains(Field.PRIMARY_FILTERS)) {
      entityToReturn.addPrimaryFilters(entity.getPrimaryFilters());
    } else {
      entityToReturn.setPrimaryFilters(null);
    }
    if (fields.contains(Field.OTHER_INFO)) {
      entityToReturn.addOtherInfo(entity.getOtherInfo());
    } else {
      entityToReturn.setOtherInfo(null);
    }
    return entityToReturn;
  }

  private static boolean matchFilter(Map<String, Object> tags,
      NameValuePair filter) {
    Object value = tags.get(filter.getName());
    if (value == null) { // doesn't have the filter
      return false;
    } else if (!value.equals(filter.getValue())) { // doesn't match the filter
      return false;
    }
    return true;
  }

  private static boolean matchPrimaryFilter(Map<String, Set<Object>> tags,
      NameValuePair filter) {
    Set<Object> value = tags.get(filter.getName());
    if (value == null) { // doesn't have the filter
      return false;
    } else {
      return value.contains(filter.getValue());
    }
  }

  private static Object maybeConvert(Object o) {
    if (o instanceof Long) {
      Long l = (Long)o;
      if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
        return l.intValue();
      }
    }
    return o;
  }

}

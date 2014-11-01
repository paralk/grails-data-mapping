/* Copyright (C) 2010 SpringSource
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.datastore.mapping.cassandra;

import groovy.util.ConfigObject;
import groovy.util.ConfigSlurper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import org.grails.datastore.gorm.cassandra.mapping.BasicCassandraMappingContext;
import org.grails.datastore.gorm.cassandra.mapping.MappingCassandraConverter;
import org.grails.datastore.gorm.cassandra.mapping.TimeZoneToStringConverter;
import org.grails.datastore.mapping.cassandra.config.CassandraMappingContext;
import org.grails.datastore.mapping.cassandra.utils.EnumUtil;
import org.grails.datastore.mapping.core.AbstractDatastore;
import org.grails.datastore.mapping.core.Session;
import org.grails.datastore.mapping.core.SoftThreadLocalMap;
import org.grails.datastore.mapping.model.DatastoreConfigurationException;
import org.grails.datastore.mapping.model.MappingContext;
import org.grails.datastore.mapping.model.PersistentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.cassandra.config.CassandraCqlClusterFactoryBean;
import org.springframework.cassandra.config.KeyspaceAction;
import org.springframework.cassandra.config.KeyspaceActionSpecificationFactoryBean;
import org.springframework.cassandra.config.KeyspaceAttributes;
import org.springframework.cassandra.core.WriteOptions;
import org.springframework.cassandra.core.keyspace.KeyspaceActionSpecification;
import org.springframework.cassandra.core.keyspace.KeyspaceOption.ReplicationStrategy;
import org.springframework.cassandra.support.CassandraExceptionTranslator;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.cassandra.config.SchemaAction;
import org.springframework.data.cassandra.core.CassandraAdminTemplate;
import org.springframework.data.cassandra.core.CassandraTemplate;
import org.springframework.util.Assert;

import com.datastax.driver.core.Cluster;

/**
 * A Datastore implementation for Cassandra. Uses Spring Data Cassandra Factory
 * beans to create and initialise the Cassandra driver cluster and session
 * 
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public class CassandraDatastore extends AbstractDatastore implements InitializingBean, DisposableBean, MappingContext.Listener {

	private static Logger log = LoggerFactory.getLogger(CassandraDatastore.class);
	// TODO make one keyspace for each session somehow, maybe just do a
	// different datastore instance?
	public static final String DEFAULT_KEYSPACE = "CassandraKeySpace";
	public static final SchemaAction DEFAULT_SCHEMA_ACTION = SchemaAction.NONE;
	public static final String CONTACT_POINTS = "contactPoints";
	public static final String PORT = "port";
	public static final String SCHEMA_ACTION = "dbCreate";
	public static final String KEYSPACE_CONFIG = "keyspace";
	public static final String KEYSPACE_NAME = "name";
	public static final String KEYSPACE_ACTION = "action";
	public static final String KEYSPACE_DURABLE_WRITES = "durableWrites";
	public static final String KEYSPACE_REPLICATION_FACTOR = "replicationFactor";
	public static final String KEYSPACE_REPLICATION_STRATEGY = "replicationStrategy";
	public static final String KEYSPACE_NETWORK_TOPOLOGY = "networkTopology";

	protected ConfigObject configuration = new ConfigObject();
	protected Cluster nativeCluster;
	protected com.datastax.driver.core.Session nativeSession;
	protected BasicCassandraMappingContext springCassandraMappingContext;
	protected CassandraTemplate cassandraTemplate;
	protected CassandraAdminTemplate cassandraAdminTemplate;
	protected CassandraCqlClusterFactoryBean cassandraCqlClusterFactoryBean;
	protected KeyspaceActionSpecificationFactoryBean keyspaceActionSpecificationFactoryBean;
	protected GormCassandraSessionFactoryBean cassandraSessionFactoryBean;
	protected boolean stateless = false;
	protected String keyspace;	
	
	private static final SoftThreadLocalMap PERSISTENCE_OPTIONS_MAP = new SoftThreadLocalMap();

	public CassandraDatastore() {
		this(new CassandraMappingContext(), Collections.<String, String> emptyMap(), null);
	}

	public CassandraDatastore(Map<String, String> connectionDetails, ConfigurableApplicationContext ctx) {
		this(new CassandraMappingContext(), connectionDetails, ctx);
	}

	public CassandraDatastore(CassandraMappingContext mappingContext, Map<String, String> connectionDetails, ConfigurableApplicationContext ctx) {
		super(mappingContext, connectionDetails, ctx);
		// Groovy can pass in any of the below to connectionDetails parameter,
		// prefer a ConfigObject and not a flattened map so we have proper
		// access to any nested maps
		if (connectionDetails instanceof ConfigObject) {
			this.configuration = (ConfigObject) connectionDetails;
		} else if ((Map) connectionDetails instanceof Properties) {
			this.configuration = new ConfigSlurper().parse((Properties) (Map) connectionDetails);
		} else if (connectionDetails != null) {
			for (Entry<String, String> entry : connectionDetails.entrySet()) {
				this.configuration.put(entry.getKey(), entry.getValue());
			}
		}
		this.keyspace = mappingContext.getKeyspace();
		Assert.hasText(keyspace, "Keyspace must be set");
		springCassandraMappingContext = new BasicCassandraMappingContext(mappingContext);

		mappingContext.setSpringCassandraMappingContext(springCassandraMappingContext);
		if (mappingContext != null) {
			mappingContext.addMappingContextListener(this);
		}

		initializeConverters(mappingContext);

		log.debug("Initializing Cassandra Datastore for keyspace: " + keyspace);
	}

	@Override
	protected void initializeConverters(MappingContext mappingContext) {
		super.initializeConverters(mappingContext);
		mappingContext.getConverterRegistry().addConverter(new TimeZoneToStringConverter());
	}

	public void setCassandraCqlClusterFactoryBean(CassandraCqlClusterFactoryBean cassandraCqlClusterFactoryBean) {
		this.cassandraCqlClusterFactoryBean = cassandraCqlClusterFactoryBean;
	}

	public void setKeyspaceActionSpecificationFactoryBean(KeyspaceActionSpecificationFactoryBean keyspaceActionSpecificationFactoryBean) {
		this.keyspaceActionSpecificationFactoryBean = keyspaceActionSpecificationFactoryBean;
	}

	public void setCassandraSessionFactoryBean(GormCassandraSessionFactoryBean cassandraSessionFactoryBean) {
		this.cassandraSessionFactoryBean = cassandraSessionFactoryBean;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		createCluster();
		createNativeSession();
	}

	protected Cluster createCluster() throws Exception {
		if (nativeCluster == null) {
			if (cassandraCqlClusterFactoryBean == null) {
				cassandraCqlClusterFactoryBean = new CassandraCqlClusterFactoryBean();
			}
			cassandraCqlClusterFactoryBean.setContactPoints(read(String.class, CONTACT_POINTS, configuration, CassandraCqlClusterFactoryBean.DEFAULT_CONTACT_POINTS));
			cassandraCqlClusterFactoryBean.setPort(read(Integer.class, PORT, configuration, CassandraCqlClusterFactoryBean.DEFAULT_PORT));
			Set<KeyspaceActionSpecification<?>> keyspaceSpecifications = createKeyspaceSpecifications();
			cassandraCqlClusterFactoryBean.setKeyspaceSpecifications(keyspaceSpecifications);
			cassandraCqlClusterFactoryBean.afterPropertiesSet();
			nativeCluster = cassandraCqlClusterFactoryBean.getObject();
			if (nativeCluster == null) {
				throw new DatastoreConfigurationException("Cassandra driver cluster not created");
			}
		}
		return nativeCluster;
	}

	protected Set<KeyspaceActionSpecification<?>> createKeyspaceSpecifications() {
		Set<KeyspaceActionSpecification<?>> specifications = Collections.emptySet();
		Object object = configuration.get(KEYSPACE_CONFIG);
		if (object instanceof ConfigObject) {
			ConfigObject keyspaceConfiguration = (ConfigObject) object;
			KeyspaceAction keyspaceAction = readKeyspaceAction(keyspaceConfiguration);

			if (keyspaceAction != null) {
				log.info("Set keyspace generation strategy to '" + keyspaceConfiguration.get(KEYSPACE_ACTION) + "'");
				if (keyspaceActionSpecificationFactoryBean == null) {
					keyspaceActionSpecificationFactoryBean = new KeyspaceActionSpecificationFactoryBean();
				}
				keyspaceActionSpecificationFactoryBean.setName(keyspace);
				keyspaceActionSpecificationFactoryBean.setAction(keyspaceAction);
				keyspaceActionSpecificationFactoryBean.setDurableWrites(read(Boolean.class, KEYSPACE_DURABLE_WRITES, keyspaceConfiguration, KeyspaceAttributes.DEFAULT_DURABLE_WRITES));
				ReplicationStrategy replicationStrategy = EnumUtil.findEnum(ReplicationStrategy.class, KEYSPACE_REPLICATION_STRATEGY, keyspaceConfiguration, KeyspaceAttributes.DEFAULT_REPLICATION_STRATEGY);
				keyspaceActionSpecificationFactoryBean.setReplicationStrategy(replicationStrategy);

				if (replicationStrategy == ReplicationStrategy.SIMPLE_STRATEGY) {
					keyspaceActionSpecificationFactoryBean.setReplicationFactor(read(Long.class, KEYSPACE_REPLICATION_FACTOR, keyspaceConfiguration, KeyspaceAttributes.DEFAULT_REPLICATION_FACTOR));
				} else if (replicationStrategy == ReplicationStrategy.NETWORK_TOPOLOGY_STRATEGY) {

					ConfigObject networkTopology = read(ConfigObject.class, KEYSPACE_NETWORK_TOPOLOGY, keyspaceConfiguration, null);
					if (networkTopology != null) {
						List<String> dataCenters = new ArrayList<String>();
						List<String> replicationFactors = new ArrayList<String>();
						for (Object o : networkTopology.entrySet()) {
							Entry entry = (Entry) o;
							dataCenters.add(String.valueOf(entry.getKey()));
							replicationFactors.add(String.valueOf(entry.getValue()));
						}
						keyspaceActionSpecificationFactoryBean.setNetworkTopologyDataCenters(dataCenters);
						keyspaceActionSpecificationFactoryBean.setNetworkTopologyReplicationFactors(replicationFactors);
					}
				}

				keyspaceActionSpecificationFactoryBean.setIfNotExists(true);
				try {
					keyspaceActionSpecificationFactoryBean.afterPropertiesSet();
					specifications = keyspaceActionSpecificationFactoryBean.getObject();

				} catch (Exception e) {
					throw new DatastoreConfigurationException(String.format("Failed to create keyspace [%s] ", keyspace), e);
				}
			}
		}
		return specifications;
	}

	protected com.datastax.driver.core.Session createNativeSession() throws ClassNotFoundException, Exception {
		if (nativeSession == null) {
			Assert.notNull(nativeCluster, "Cassandra driver cluster not created");
			if (cassandraSessionFactoryBean == null) {
				cassandraSessionFactoryBean = new GormCassandraSessionFactoryBean(mappingContext, springCassandraMappingContext);
			}
			cassandraSessionFactoryBean.setCluster(nativeCluster);
			cassandraSessionFactoryBean.setKeyspaceName(this.keyspace);
			MappingCassandraConverter mappingCassandraConverter = new MappingCassandraConverter(cassandraMapping());
			cassandraSessionFactoryBean.setConverter(mappingCassandraConverter);
			cassandraSessionFactoryBean.setSchemaAction(readSchemaAction());
			log.info("Set Cassandra db generation strategy to '" + (configuration.get(SCHEMA_ACTION) != null ? configuration.get(SCHEMA_ACTION) : "none") + "'");
			// TODO: startup and shutdown scripts addition
			cassandraSessionFactoryBean.afterPropertiesSet();
			nativeSession = cassandraSessionFactoryBean.getObject();
			cassandraTemplate = new CassandraTemplate(nativeSession, mappingCassandraConverter);
			cassandraTemplate.setExceptionTranslator(new CassandraExceptionTranslator());
			cassandraAdminTemplate = new CassandraAdminTemplate(nativeSession, mappingCassandraConverter);
		}
		return nativeSession;
	}

	protected org.springframework.data.cassandra.mapping.CassandraMappingContext cassandraMapping() throws ClassNotFoundException {
		Collection<PersistentEntity> persistentEntities = mappingContext.getPersistentEntities();
		Set<Class<?>> entitySet = new HashSet<Class<?>>();
		for (PersistentEntity persistentEntity : persistentEntities) {
			entitySet.add(persistentEntity.getJavaClass());
		}
		springCassandraMappingContext.setInitialEntitySet(entitySet);
		springCassandraMappingContext.afterPropertiesSet();

		return springCassandraMappingContext;
	}

	@Override
	protected Session createSession(Map<String, String> connectionDetails) {
		if (stateless) {
			return createStatelessSession(connectionDetails);
		} else {
			return new CassandraSession(this, getMappingContext(), this.nativeSession, getApplicationEventPublisher(), false, cassandraTemplate);
		}
	}

	@Override
	protected Session createStatelessSession(Map<String, String> connectionDetails) {
		return new CassandraSession(this, getMappingContext(), this.nativeSession, getApplicationEventPublisher(), true, cassandraTemplate);
	}

	@Override
	public void persistentEntityAdded(PersistentEntity entity) {
		// get call here adds a persistententity to
		// springCassandraMappingContext
		springCassandraMappingContext.getPersistentEntity(entity.getJavaClass());
	}

	public Cluster getNativeCluster() {
		return nativeCluster;
	}

	public com.datastax.driver.core.Session getNativeSession() {
		return nativeSession;
	}

	public CassandraTemplate getCassandraTemplate() {
		return cassandraTemplate;
	}

	public void createTableDefinition(Class<?> cls) {
		cassandraSessionFactoryBean.createTable(cls);
	}

	public void setWriteOptions(final Object o, WriteOptions writeOptions) {
		if (o != null && writeOptions != null) {
			getPersistenceOptionsMap(o).put("writeOptions", writeOptions);
		}
	}

	public WriteOptions getWriteOptions(final Object o) {
		return (WriteOptions) getPersistenceOptionsMap(o).get("writeOptions");
	}

	@Override
	public void destroy() throws Exception {
		super.destroy();
		PERSISTENCE_OPTIONS_MAP.remove();
		if (cassandraSessionFactoryBean != null) {
			cassandraSessionFactoryBean.destroy();
		}
		if (cassandraCqlClusterFactoryBean != null) {				
			cassandraCqlClusterFactoryBean.destroy();
		}
	}

	private <T> T read(Class<T> type, String key, ConfigObject config, T defaultValue) {
		Object value = config.get(key);
		return value == null ? defaultValue : mappingContext.getConversionService().convert(value, type);
	}

	private Map<String, Object> getPersistenceOptionsMap(final Object o) {
		Map<String, Object> persistenceOptionsMap = (Map<String, Object>) PERSISTENCE_OPTIONS_MAP.get().get(System.identityHashCode(o));
		if (persistenceOptionsMap == null) {
			persistenceOptionsMap = new HashMap<String, Object>();
			PERSISTENCE_OPTIONS_MAP.get().put(System.identityHashCode(o), persistenceOptionsMap);
		}
		return persistenceOptionsMap;
	}
	
	private KeyspaceAction readKeyspaceAction(ConfigObject keyspaceConfiguration) {		
		Map<String, KeyspaceAction> keyspaceActionMap = new HashMap<String, KeyspaceAction>();
		keyspaceActionMap.put("create", KeyspaceAction.CREATE);
		keyspaceActionMap.put("create-drop", KeyspaceAction.CREATE_DROP);
		return EnumUtil.findMatchingEnum(KEYSPACE_ACTION, keyspaceConfiguration.get(KEYSPACE_ACTION), keyspaceActionMap, null);		
	}
	
	private SchemaAction readSchemaAction() {		
		Map<String, SchemaAction> schemaActionMap = new HashMap<String, SchemaAction>();
		schemaActionMap.put("none", SchemaAction.NONE);
		schemaActionMap.put("create", SchemaAction.CREATE);
		schemaActionMap.put("recreate", SchemaAction.RECREATE);
		schemaActionMap.put("recreate-drop-unused", SchemaAction.RECREATE_DROP_UNUSED);
		return EnumUtil.findMatchingEnum(SCHEMA_ACTION, configuration.get(SCHEMA_ACTION), schemaActionMap, SchemaAction.NONE);		
	}
}

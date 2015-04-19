package org.grails.datastore.gorm.cassandra

import grails.gorm.tests.GormDatastoreSpec;
import grails.gorm.tests.TestEntity;
import grails.persistence.Entity


class WriteOptionSpec extends GormDatastoreSpec {

	@Override
	public List getDomainClasses() {
		
	}
	
	
	void "Test that the WriteOptions are used to save the entity"() {
		when: "An object is saved"
			def testEntity = new TestEntity(name: "Bob")			
			testEntity.save(writeOptions: [consistencyLevel: "THREE", ttl: 5, retryPolicy: "DOWNGRADING_CONSISTENCY"], flush: true)
			testEntity.age = 10
			testEntity.save(writeOptions: [consistencyLevel: "ALL", ttl: 5, retryPolicy: "DOWNGRADING_CONSISTENCY"], flush: true)
			/*
			Thread.start {
				TestEntity.withNewSession { s ->					
					testEntity.save(writeOptions: [consistencyLevel: "ALL", ttl: 1, retryPolicy: "DOWNGRADING_CONSISTENCY"], flush: true)
				}
			}.join(*/
			
		then:
			testEntity
	}
}

@Entity
class WriteOptionsEntity {
	String name
	
	static mapping = {
		
	}
}
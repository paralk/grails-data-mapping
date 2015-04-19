package org.grails.datastore.gorm.cassandra

import grails.gorm.tests.GormDatastoreSpec
import grails.gorm.tests.Person

class BatchSpec extends GormDatastoreSpec {
	
	def "Test that many objects can be inserted as a batch"() {
		given:
			def bob = new Person(firstName:"Bob", lastName:"Builder")
			def fred = new Person(firstName:"Fred", lastName:"Flintstone")
			def joe = new Person(firstName:"Joe", lastName:"Doe")

			Person.saveAll(bob, fred, joe)
			session.batchFlush()

		when:
			def total = Person.count()
			def results = Person.list()
		then:
			total == 3
			results.every { it.id != null } == true
	}
}

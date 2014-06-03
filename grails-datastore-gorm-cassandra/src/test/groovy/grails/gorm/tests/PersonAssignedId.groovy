package grails.gorm.tests

import grails.gorm.CassandraEntity
import grails.persistence.Entity

import org.grails.datastore.gorm.query.transform.ApplyDetachedCriteriaTransform

@ApplyDetachedCriteriaTransform
@groovy.transform.EqualsAndHashCode 
@CassandraEntity
class PersonAssignedId implements Serializable, Comparable<Person> {      
    Long version
    String firstName
    String lastName
    Integer age = 0    

    static Person getByFirstNameAndLastNameAndAge(String firstName, String lastName, int age) {
        find( new Person(firstName: firstName, lastName: lastName, age: age) )
    }

    static mapping = {
        id name:"firstName", primaryKey:[ordinal:0, type:"partitioned"], generator:"assigned"       
        lastName index:true, primaryKey:[ordinal:1, type: "clustered"]
        age index:true, primaryKey:[ordinal:2, type: "clustered"]
    }

    static constraints = {        
    }

    @Override
    int compareTo(Person t) {
        age <=> t.age
    }
}
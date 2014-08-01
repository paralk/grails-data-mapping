package grails.gorm.tests

import grails.gorm.CassandraEntity

@CassandraEntity
class EnumThingEnumPartitionKey {
    TestEnum en
    String name
   

    static mapping = {
        id name:"en", primaryKey:[ordinal:0, type:"partitioned"], generator:"assigned"
        name primaryKey:[ordinal:1, type: "clustered"]
    }
        
}
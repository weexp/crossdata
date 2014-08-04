/*
 * Licensed to STRATIO (C) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership.  The STRATIO (C) licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.stratio.meta.core.grammar.statements;

import com.stratio.meta.core.grammar.ParsingTest;
import com.stratio.meta.core.statements.MetaStatement;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.Set;

import static org.testng.Assert.*;

public class CreateKeyspaceStatementTest extends ParsingTest {

    @Test
    public void createKeyspaceIfNotExists() {
        String inputText = "CREATE KEYSPACE IF NOT EXISTS key_space1 "
                + "WITH replication = replicationLevel AND durable_writes = false;";
        testRegularStatement(inputText, "createKeyspaceIfNotExists");
    }

    //TODO: Should we support it in this way?
    @Test
    public void createKeyspaceNestedOptions() {
        String inputText = "CREATE KEYSPACE IF NOT EXISTS key_space1 "
                + "WITH replication = {class: NetworkTopologyStrategy, DC1: 1, DC2: 3} "
                +"AND durable_writes = false;";
        Set<String> properties = new HashSet<>();
        properties.add("class: NetworkTopologyStrategy");
        properties.add("DC1: 1");
        properties.add("DC2: 3");
        MetaStatement st = parser.parseStatement(inputText).getStatement();
        String propResultStr = st.toString().substring(st.toString().indexOf("{")+1, st.toString().indexOf("}"));
        String[] str = propResultStr.split(",");
        Set<String> propertiesResult = new HashSet<>();
        for (String str1 : str) {
            propertiesResult.add(str1.trim());
        }
        assertNotNull(st, "Cannot parse create keyspace - nestedOptions");
        assertEquals("CREATE KEYSPACE IF NOT EXISTS key_space1 WITH replication = {",
                st.toString().substring(0, st.toString().indexOf("{")+1),
                "Cannot parse create keyspace - nestedOptions");
        assertEquals("} AND durable_writes = false;",
                st.toString().substring(st.toString().indexOf("}"))+";",
                "Cannot parse create keyspace - nestedOptions");
        assertTrue(propertiesResult.containsAll(properties), "Cannot parse create keyspace - nestedOptions");
        assertTrue(properties.containsAll(propertiesResult), "Cannot parse create keyspace - nestedOptions");
    }

    @Test
    public void createKeyspaceBasicOptions() {
        String inputText = "CREATE KEYSPACE key_space1 WITH replication = {class: SimpleStrategy, replication_factor: 1}"
                + " AND durable_writes = false;";
        MetaStatement st = parser.parseStatement(inputText).getStatement();
        assertNotNull(st, "Cannot parse createKeyspaceBasicOptions");

        boolean originalOK = false;
        boolean alternative1 = false;

        if(inputText.equalsIgnoreCase(st.toString()+";")){
            originalOK = true;
        }

        String alternative1Str = "CREATE KEYSPACE key_space1 WITH replication = {replication_factor: 1, class: SimpleStrategy}"
                + " AND durable_writes = false;";
        if(alternative1Str.equalsIgnoreCase(st.toString()+";")){
            alternative1 = true;
        }

        assertTrue((originalOK || alternative1), "Cannot parse createKeyspaceBasicOptions");
    }

    @Test
    public void createKeyspaceDurableWrites() {
        String inputText = "CREATE KEYSPACE demo WITH replication = {class: SimpleStrategy, replication_factor: 1} "
                + "AND durable_writes = false;";
        MetaStatement st = parser.parseStatement(inputText).getStatement();
        assertNotNull(st, "Cannot parse createKeyspaceDurableWrites");

        boolean originalOK = false;
        boolean alternative1 = false;

        if(inputText.equalsIgnoreCase(st.toString()+";")){
            originalOK = true;
        }

        String alternative1Str = "CREATE KEYSPACE demo WITH replication = {replication_factor: 1, class: SimpleStrategy} "
                + "AND durable_writes = false;";
        if(alternative1Str.equalsIgnoreCase(st.toString()+";")){
            alternative1 = true;
        }

        assertTrue((originalOK || alternative1), "Cannot parse createKeyspaceDurableWrites");
    }

    @Test
    public void createKeyspaceWrongIdentifier(){
        String inputText = "CREATE KEYSPACE name.key_space1 WITH replication = replicationLevel;";
        testParseFails(inputText, "createKeyspaceWrongIdentifier");
    }


}

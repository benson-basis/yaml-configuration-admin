/*
* Copyright 2015 Basis Technology Corp.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.basistech.yca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.io.Resources;
import org.junit.Test;

import java.net.URL;
import java.util.Dictionary;

import static org.junit.Assert.assertEquals;

/**
 *
 */
public class FlattenerTest {

    @Test
    public void flattenSomething() throws Exception {
        URL testDocUrl = Resources.getResource(FlattenerTest.class, "flatten-test.yaml");
        JsonNode node = new ObjectMapper(new YAMLFactory()).readTree(testDocUrl);
        Dictionary<String, ?> dict = JsonNodeFlattener.flatten(node);
        assertEquals("blue", dict.get("color"));
        assertEquals("arms", dict.get("limbs[0]"));
        assertEquals("legs", dict.get("limbs[1]"));
        assertEquals(1.0, (Double)dict.get("ingredients.stuffing.thickness"), 0.001);
        assertEquals("a", dict.get("ingredients.topping[0]"));
        assertEquals("dog", dict.get("ingredients.topping[2].cat"));
    }
}

/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.integtests.resolve.capabilities

import org.gradle.integtests.fixtures.AbstractIntegrationSpec

class CapabilitiesLocalComponentIntegrationTest extends AbstractIntegrationSpec {

    def "can detect conflict between local projects providing the same capability"() {
        given:
        settingsFile << """
            rootProject.name = 'test'
            include 'b'
        """
        buildFile << """
            apply plugin: 'java-library'
            
            configurations.api.outgoing {
                capability 'org:capability:1.0'
            }

            dependencies {
                api project(":b")
            }
            
        """
        file('b/build.gradle') << """
            apply plugin: 'java-library'
            
            configurations.api.outgoing {
                capability group:'org', name:'capability', version:'1.0'
            }
        """

        when:
        fails 'dependencies'

        then:
        failure.assertHasCause("Cannot choose between :test:unspecified and test:b:unspecified because they provide the same capability: org:capability:1.0")
    }

}


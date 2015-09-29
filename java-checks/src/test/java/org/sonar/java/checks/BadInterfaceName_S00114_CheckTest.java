/*
 * SonarQube Java
 * Copyright (C) 2012 SonarSource
 * sonarqube@googlegroups.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.java.checks;

import org.junit.Test;
import org.sonar.java.checks.verifier.JavaCheckVerifier;

public class BadInterfaceName_S00114_CheckTest {

  @Test
  public void test() {
    JavaCheckVerifier.verify("src/test/files/checks/BadInterfaceNameNoncompliant.java", new BadInterfaceName_S00114_Check());
  }

  @Test
  public void test2() {
    BadInterfaceName_S00114_Check check = new BadInterfaceName_S00114_Check();
    check.format = "^[a-zA-Z0-9]*$";
    JavaCheckVerifier.verifyNoIssue("src/test/files/checks/BadInterfaceName.java", check);
  }

}

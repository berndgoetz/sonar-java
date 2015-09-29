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

public class BadPackageName_S00120_CheckTest {

  @Test
  public void test() {
    JavaCheckVerifier.verify("src/test/files/checks/PACKAGE/BadPackageNameNoncompliant.java", new BadPackageName_S00120_Check());
  }

  @Test
  public void test2() {
    BadPackageName_S00120_Check check = new BadPackageName_S00120_Check();
    check.format = "^[a-zA-Z0-9]*$";
    JavaCheckVerifier.verifyNoIssue("src/test/files/checks/PACKAGE/BadPackageName.java", check);
  }

  @Test
  public void test3() {
    JavaCheckVerifier.verify("src/test/files/checks/PACKAGE/BadQualifiedIdentifierPackageName.java", new BadPackageName_S00120_Check());
  }
}

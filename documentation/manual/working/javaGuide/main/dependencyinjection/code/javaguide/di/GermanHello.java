/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */

package javaguide.di;

public class GermanHello implements Hello {
  @Override
  public String sayHello(String name) {
    return "Hallo " + name;
  }
}

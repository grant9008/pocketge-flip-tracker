package javax.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/* Faithful to the real javax.inject.Inject, and RUNTIME on purpose: this
   annotation used to be stubbed with default (CLASS) retention, so reflection
   could not see it and InjectionWiringTest had nothing to read. The real
   annotation is RUNTIME, so a stub that is not lies about the one thing that
   test exists to check. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface Inject { }

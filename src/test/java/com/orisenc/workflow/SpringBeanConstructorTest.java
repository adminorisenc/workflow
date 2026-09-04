package com.orisenc.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

/**
 * Every Spring bean must have a constructor Spring can actually choose.
 *
 * <p>Written after Common Platform failed to start for the first time. Several services here use two
 * constructors - a public one for Spring and a package-private one taking a fixed {@link
 * java.time.Clock} for tests. With neither annotated, Spring cannot pick between them, falls back to
 * looking for a no-arg constructor, and the context dies with {@code No default constructor found}.
 *
 * <p>What makes it worth a test rather than a code review note is that <em>no unit test can see
 * it</em>. The tests construct these classes directly through the package-private constructor, so
 * they pass, the build is green, and the defect appears only when someone starts the application.
 * Four classes across three services carried it simultaneously - {@code MasterDataService},
 * {@code PartyDetailService}, Workflow's {@code ProjectTaskService} and Accounts' {@code
 * AgingLadder} - because until that first boot, nothing had ever asked Spring to instantiate them.
 *
 * <p>Scanning is done with Spring's own component provider rather than by reading source, so the
 * question asked here is exactly the question the container asks at startup.
 *
 * <p>Copied per service rather than shared. Each service is an independent Gradle build with no
 * common test library, and the same reasoning that put a copy of {@code RequiredPermissions} in
 * each applies: a second copy is cheaper than a service that cannot start.
 */
class SpringBeanConstructorTest {

  private static final String BASE_PACKAGE = "com.orisenc.workflow";

  @Test
  void everyComponentHasAConstructorSpringCanChoose() {
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

    var ambiguous = new ArrayList<String>();
    for (var candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
      String name = candidate.getBeanClassName();
      if (name == null) continue;
      Class<?> type;
      try {
        type = Class.forName(name);
      } catch (ClassNotFoundException e) {
        continue;
      }
      Constructor<?>[] constructors = type.getDeclaredConstructors();
      if (constructors.length <= 1) continue;

      boolean hasNoArg = Arrays.stream(constructors).anyMatch(c -> c.getParameterCount() == 0);
      long annotated = Arrays.stream(constructors)
          .filter(c -> c.isAnnotationPresent(Autowired.class))
          .count();
      // Spring resolves this if exactly one constructor is annotated, or if a no-arg one exists to
      // fall back to. Anything else is the startup failure described above.
      if (annotated != 1 && !hasNoArg)
        ambiguous.add(type.getSimpleName() + " (" + constructors.length + " constructors, none @Autowired)");
    }

    assertThat(ambiguous)
        .as("Spring cannot choose a constructor for these, so the context fails to start with "
            + "\"No default constructor found\". Annotate the intended one with @Autowired.")
        .isEmpty();
  }
}

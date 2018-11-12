package org.togglz.kafka;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.salesforce.kafka.test.junit4.SharedKafkaTestResource;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.togglz.core.Feature;
import org.togglz.core.activation.UsernameActivationStrategy;
import org.togglz.core.repository.FeatureState;

public class KafkaStateRepositoryTest {

  private static final String TOPIC = "feature-states";
  private static final String EMPTY_TOPIC = "no-feature-states";
  private static final Duration POLLING_TIMEOUT = Duration.ofMillis(200);

  @ClassRule
  public static final SharedKafkaTestResource sharedKafkaTestResource = new SharedKafkaTestResource();

  @BeforeClass
  public static void createTopics() {
    sharedKafkaTestResource.getKafkaTestUtils().createTopic(TOPIC, 5, (short) 1);
    sharedKafkaTestResource.getKafkaTestUtils().createTopic(EMPTY_TOPIC, 5, (short) 1);

    try (KafkaStateRepository stateRepository = createStateRepository(TOPIC)) {
      for (int i = 0; i < 100; i++) {
        Features feature = ThreadLocalRandom.current().nextBoolean() ? Features.FEATURE_A : Features.FEATURE_B;
        boolean enabled = ThreadLocalRandom.current().nextBoolean();

        stateRepository.setFeatureState(newFeatureState(feature).setEnabled(enabled));
      }
    }
  }

  @Test
  public void shouldReturnNullWhenTopicIsEmpty() {
    KafkaStateRepository stateRepository = createStateRepository(EMPTY_TOPIC);

    FeatureState receivedFeatureState = stateRepository.getFeatureState(Features.FEATURE_A);

    assertThat(receivedFeatureState).isNull();
  }

  @Test
  public void shouldReturnNullWhenFeatureHasNotBeenUsedYet() {
    KafkaStateRepository stateRepository = createStateRepository(TOPIC);

    stateRepository.setFeatureState(newFeatureState(Features.FEATURE_B).setEnabled(false));
    FeatureState receivedFeatureState = stateRepository.getFeatureState(Features.UNUSED_FEATURE);

    assertThat(receivedFeatureState).isNull();
  }

  @Test
  public void shouldUpdateFeatureStateWithSingleStateRepository() {
    KafkaStateRepository stateRepository = createStateRepository(TOPIC);
    FeatureState featureState = newFeatureState(Features.FEATURE_A).setEnabled(false);

    FeatureState receivedFeatureState = setAndGetFeatureState(stateRepository, stateRepository, featureState);

    assertThat(receivedFeatureState).isEqualToComparingFieldByField(featureState);
  }

  @Test
  public void shouldUpdateFeatureStateWithMultipleStateRepositories() {
    KafkaStateRepository sendingRepository = createStateRepository(TOPIC);
    KafkaStateRepository pollingRepository = createStateRepository(TOPIC);
    FeatureState featureState = newFeatureState(Features.FEATURE_B).setEnabled(true);

    FeatureState receivedFeatureState = setAndGetFeatureState(sendingRepository, pollingRepository, featureState);

    assertThat(receivedFeatureState).isEqualToComparingFieldByField(featureState);
  }

  @Test
  public void shouldUpdateFeatureStateWithActivationStrategy() {
    KafkaStateRepository sendingRepository = createStateRepository(TOPIC);
    KafkaStateRepository pollingRepository = createStateRepository(TOPIC);
    FeatureState featureState = newFeatureStateWithActivationStrategy(Features.FEATURE_A).setEnabled(true);

    FeatureState receivedFeatureState = setAndGetFeatureState(sendingRepository, pollingRepository, featureState);

    assertThat(receivedFeatureState).isEqualToComparingFieldByField(featureState);
  }

  @Test
  public void shouldBeRunningAfterInitialization() {
    KafkaStateRepository stateRepository = createStateRepository(TOPIC);

    assertThat(stateRepository.isRunning()).isTrue();
  }

  @Test
  public void shouldHaveNoConsumerLagAfterInitialization() {
    KafkaStateRepository stateRepository = createStateRepository(TOPIC);

    assertThat(stateRepository.consumerLag()).isZero();
  }

  private static KafkaStateRepository createStateRepository(String topic) {
    return KafkaStateRepository.builder()
        .bootstrapServers(sharedKafkaTestResource.getKafkaConnectString())
        .inboundTopic(topic)
        .outboundTopic(TOPIC)
        .pollingTimeout(POLLING_TIMEOUT)
        .initializationTimeout(Duration.ofSeconds(1))
        .build();
  }

  private static FeatureState newFeatureState(Feature feature) {
    return new FeatureState(feature);
  }

  private static FeatureState newFeatureStateWithActivationStrategy(Feature feature) {
    return new FeatureState(feature)
        .setStrategyId(UsernameActivationStrategy.ID)
        .setParameter(UsernameActivationStrategy.PARAM_USERS, "user1, user2, user3");
  }

  private static FeatureState setAndGetFeatureState(
      KafkaStateRepository sendingRepository,
      KafkaStateRepository pollingRepository,
      FeatureState featureState
  ) {
    sendingRepository.setFeatureState(featureState);
    awaitNextPoll();
    return pollingRepository.getFeatureState(featureState.getFeature());
  }

  private static void awaitNextPoll() {
    try {
      MILLISECONDS.sleep(2 * POLLING_TIMEOUT.toMillis() + 100);
    } catch (InterruptedException ignored) {
    }
  }

  public enum Features implements Feature {
    FEATURE_A, FEATURE_B, UNUSED_FEATURE
  }

}

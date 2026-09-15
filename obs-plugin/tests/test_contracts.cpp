#include "../src/async-control-client.hpp"
#include "../src/media-clock.hpp"

#include <atomic>
#include <cstdlib>
#include <future>

namespace {
void check(bool condition) {
  if (!condition) std::abort();
}
}  // namespace

int main() {
  {
    MediaClock clock;
    const uint64_t origin = 10'000'000'000ULL;
    check(clock.map(1'000'000, origin).value() == origin);
    check(clock.map(1'033'333, origin + 33'333).value() == origin + 33'333);
    check(!clock.map(-1, origin).has_value());
  }

  {
    // A short scheduling delay must not rewrite the media timeline.
    MediaClock clock;
    const int64_t source_origin = 1'000'000'000LL;
    const uint64_t obs_origin = 10'000'000'000ULL;
    check(clock.map(source_origin, obs_origin).value() == obs_origin);
    check(clock.map(source_origin + 50'000'000LL, obs_origin + 150'000'000ULL).value() ==
          obs_origin + 50'000'000ULL);
  }

  {
    // Reproduce the real OBS black-screen failure: decoder startup stalls for
    // longer than the stale-frame budget while the media timeline advances by
    // only one frame. Rebase to the live edge and keep subsequent timestamps
    // aligned instead of dropping forever at real-time speed.
    MediaClock clock;
    const int64_t source_origin = 1'000'000'000LL;
    const uint64_t obs_origin = 10'000'000'000ULL;
    check(clock.map(source_origin, obs_origin).value() == obs_origin);

    const int64_t source_after_stall = source_origin + 33'000'000LL;
    const uint64_t obs_after_stall = obs_origin + 400'000'000ULL;
    check(clock.map(source_after_stall, obs_after_stall).value() == obs_after_stall);

    check(clock.map(source_origin + 66'000'000LL, obs_origin + 433'000'000ULL).value() ==
          obs_origin + 433'000'000ULL);
  }

  {
    AsyncControlClient client;
    std::promise<void> started;
    std::promise<void> release;
    const auto release_signal = release.get_future().share();
    check(client.post([&] {
      started.set_value();
      release_signal.wait();
    }));
    started.get_future().wait();

    for (int i = 0; i < 16; ++i) {
      check(client.post([] {}));
    }
    check(!client.post([] {}));
    release.set_value();

    int urgent_runs = 0;
    check(client.post_urgent([&] {
      ++urgent_runs;
      return true;
    }));
    check(client.post_urgent([&] {
      ++urgent_runs;
      return true;
    }));
    client.stop();
    check(urgent_runs == 2);
    check(!client.post([] {}));
  }

  {
    // Prove the normal three-attempt policy without racing stop() against the
    // worker. Teardown has separate semantics and is tested below.
    AsyncControlClient client;
    std::atomic<int> attempts{0};
    std::promise<void> completed;
    check(client.post_urgent([&] {
      const int current = ++attempts;
      if (current == 3) {
        completed.set_value();
        return true;
      }
      return false;
    }));
    completed.get_future().wait();
    client.stop();
    check(attempts.load() == 3);
  }

  {
    // Once destruction starts, retry backoff must wake immediately instead of
    // draining the normal three-attempt lifecycle-critical release policy.
    AsyncControlClient client;
    std::atomic<int> attempts{0};
    std::promise<void> first_attempt;
    check(client.post_urgent([&] {
      const int current = ++attempts;
      if (current == 1) first_attempt.set_value();
      return false;
    }));
    first_attempt.get_future().wait();
    client.stop();
    check(attempts.load() == 1);
  }
}

#pragma once

#include <cstdint>
#include <limits>
#include <optional>

// Maps the phone's shared media clock into OBS's monotonic clock. The mapping
// preserves the audio/video offset carried by MPEG-TS instead of replacing it
// with the time at which a frame happened to arrive on the receiver thread.
// One instance belongs to one receiver session, so reconnects start cleanly.
class MediaClock {
 public:
  std::optional<uint64_t> map(int64_t source_ns, uint64_t obs_now_ns) {
    if (source_ns < 0) return std::nullopt;
    if (!source_origin_ns_) {
      source_origin_ns_ = source_ns;
      obs_origin_ns_ = obs_now_ns;
    }

    auto mapped = map_from_origin(source_ns);
    if (!mapped) return std::nullopt;

    // Decoder/probe startup can temporarily consume buffered media slower than
    // wall clock. If the first mapping is kept forever, a one-time startup
    // stall can leave every later frame behind OBS's 250 ms stale-frame guard;
    // the receiver then drops at real-time speed and can never catch up. Shift
    // the OBS origin forward before that guard is reached. Moving only the OBS
    // origin keeps the phone timeline (and therefore A/V offsets) intact.
    if (*mapped < obs_now_ns) {
      const uint64_t lag_ns = obs_now_ns - *mapped;
      if (lag_ns > kAutomaticRebaseLagNs) {
        if (lag_ns > (std::numeric_limits<uint64_t>::max)() - obs_origin_ns_) {
          return std::nullopt;
        }
        obs_origin_ns_ += lag_ns;
        mapped = obs_now_ns;
      }
    }

    return mapped;
  }

 private:
  static constexpr uint64_t kAutomaticRebaseLagNs = 200'000'000ULL;

  std::optional<uint64_t> map_from_origin(int64_t source_ns) const {
    const int64_t delta = source_ns - *source_origin_ns_;
    if (delta < 0) {
      const uint64_t magnitude = static_cast<uint64_t>(-(delta + 1)) + 1u;
      if (magnitude > obs_origin_ns_) return std::nullopt;
      return obs_origin_ns_ - magnitude;
    }

    const uint64_t offset = static_cast<uint64_t>(delta);
    if (offset > (std::numeric_limits<uint64_t>::max)() - obs_origin_ns_) {
      return std::nullopt;
    }
    return obs_origin_ns_ + offset;
  }

  std::optional<int64_t> source_origin_ns_;
  uint64_t obs_origin_ns_ = 0;
};

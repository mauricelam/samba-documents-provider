/*
 * Copyright 2017 Google Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

#include <logger/logger.h>
#include "CredentialCache.h"

namespace SambaClient {

struct CredentialTuple emptyTuple_;

struct CredentialTuple CredentialCache::get(const std::string &key) const {
  auto& map = tempMode_ ? tempCredentialMap_ : credentialMap_;
  if (map.find(key) != map.end()) {
    LOGV("FINDME", "Credential found for %s", key.c_str());
    return map.at(key);
  } else {
    LOGV("FINDME", "No credential found for %s", key.c_str());
    return emptyTuple_;
  }
}

void CredentialCache::put(const char *key, const struct CredentialTuple &tuple) {
  auto& map = tempMode_ ? tempCredentialMap_ : credentialMap_;
  map[key] = tuple;
}

void CredentialCache::remove(const char *key_) {
  auto& map = tempMode_ ? tempCredentialMap_ : credentialMap_;
  std::string key(key_);
  map.erase(key);
}

void CredentialCache::setTempMode(const bool temp) {
  tempMode_ = temp;
}
}

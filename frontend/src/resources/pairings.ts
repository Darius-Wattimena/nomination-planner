import {useCallback} from "react";
import type {Pairing, User} from "../models/Types";
import Api from "./Api";
import {useQuery} from "./client";

/** Who somebody nominated with, nothing is loaded without an id */
export function usePairings(userId: string | undefined) {
  const pairings = useQuery<Pairing[]>(userId ? Api.fetchPairings(userId) : undefined)
  const {data} = pairings
  // The same functions until the data changes, so lists worked out with them can be kept
  const pairingWith = useCallback((user: User) => data?.find(it => it.osuId === user.osuId), [data])
  const rankedWith = useCallback((user: User) => pairingWith(user)?.pairingRanked ?? 0, [pairingWith])
  return {...pairings, pairingWith, rankedWith}
}

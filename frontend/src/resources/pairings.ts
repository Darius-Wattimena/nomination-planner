import type {Pairing, User} from "../models/Types";
import Api from "./Api";
import {useQuery} from "./client";

/** Who somebody nominated with, nothing is loaded without an id */
export function usePairings(userId: string | undefined) {
  const pairings = useQuery<Pairing[]>(userId ? Api.fetchPairings(userId) : undefined)
  const pairingWith = (user: User) => pairings.data?.find(it => it.osuId === user.osuId)
  return {...pairings, pairingWith, rankedWith: (user: User) => pairingWith(user)?.pairingRanked ?? 0}
}

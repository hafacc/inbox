# Word lists

`eff_large_wordlist.txt` is the Electronic Frontier Foundation's large wordlist for passphrases
(7,776 words, one per five dice rolls), downloaded unchanged from
https://www.eff.org/files/2016/07/18/eff_large_wordlist.txt (SHA-256
`addd35536511597a02fa0a9ff1e5284677b8883b83e986e43f15a3db996b903e`). It is published by the EFF
under the Creative Commons Attribution 3.0 United States license
(https://creativecommons.org/licenses/by/3.0/us/); see https://www.eff.org/dice.

Recovery phrases use it (`shared/src/pgp/recovery.ts`, `EFF_LARGE_WORDLIST` in
`shared/src/pgp/eff-wordlist.ts`, which holds the same words in the same order). Android loads this
file as a resource.

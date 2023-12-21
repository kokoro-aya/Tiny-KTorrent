## Tiny-KTorrent
[![FOSSA Status](https://app.fossa.com/api/projects/git%2Bgithub.com%2Fkokoro-aya%2FTiny-KTorrent.svg?type=shield)](https://app.fossa.com/projects/git%2Bgithub.com%2Fkokoro-aya%2FTiny-KTorrent?ref=badge_shield)


This is a simple BitTorrent client written in Kotlin. The architecture is largely based on [the c++ implementation by ss16118](https://github.com/ss16118/torrent-client-cpp/)

Here are some main reference blog posts:

- [Building a BitTorrent client from the ground up in Go](https://blog.jse.li/posts/torrent/)
- [重复造轮子的喜悦：从零开始用C++写一个最基础的BitTorrent客户端](https://zhuanlan.zhihu.com/p/386437665)
- [Kotlin Bencode Decoding and Encoding](https://blog.matthewbrunelle.com/projects/2018/07/29/kotlin-bencode.html)

~~Currently the code works only on seeds from a site which is called AcademicTorrents (a seed from this site is provided in repo), on single file.~~

Compact or not compact format are supported.

It doesn't support pause and resume, multi-file or seeding, nor does it support pipeline or other advanced features.

### Launch Parameters

| Options | Full Name | Desc | Misc |
| ------- | --------- | ---- | ---- |
| -s | --seed | Path to the Torrent seed | REQUIRED |
| -o | --output | The output directory to store the downloaded file | REQUIRED |
| -n | --threadnum | Number of downloading coroutines to use | 8 |
| -l | --logging | Enable logging | false (not present in parameters) |
| -f | --logfile | The directory to store the log file | `./logs/ktorrent` |

If no argument is supplied or wrong arguments, the help menu will be shown.

### Known Issues

- While connecting to a certain types of peers, the handshake will fail (the socket tries to read 68 bytes but it received an EOF)
- ~~Not all coroutines are working (if there are 3~4/8 it's already good)~~ FIXED?
- Timeout for TCP connection is too long
- After downloading several pieces (around a hundred of), the program hangs and all coroutines are paused.
  
  This seems to be an issue related to a failed socket connection initialization. Sometimes it happens just after the program
  is launched but sometimes it has to wait until it's blocked.

### Implemented

- Bencode/Decode
- Retrieve a list of peers from tracker periodically
- Display download status
- Download single file with multi coroutines

## License
[![FOSSA Status](https://app.fossa.com/api/projects/git%2Bgithub.com%2Fkokoro-aya%2FTiny-KTorrent.svg?type=large)](https://app.fossa.com/projects/git%2Bgithub.com%2Fkokoro-aya%2FTiny-KTorrent?ref=badge_large)
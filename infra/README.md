# Running the planner on AWS

`planner.yml` is a CloudFormation template for the whole planner on one address:

```
                       ┌─ everything else ──> S3 bucket (private, the website)
visitor ──> CloudFront ┤
  https     (address)  └─ /v2/*, /oauth2/*,  ──> one Elastic Beanstalk instance
                          /login/oauth2/*        nginx (https, Let's Encrypt) ──> Tomcat 11, Java 21
                                                 only reachable by CloudFront
```

- **One address.** The website and the backend share an address, so no CORS, and the login cookie stays first-party.
- **No load balancer.** The planner keeps the beatmaps in memory, so it runs on one instance anyway. A load balancer would only have held the certificate, for about $18 a month plus two IPv4 addresses.
- **SSL** for visitors comes from the existing free Amazon certificate for `*.greaper.net` in us-east-1. Between CloudFront and the instance, the instance gets its own free Let's Encrypt certificate for the origin address (`backend/.platform`). Let's Encrypt checks it through a TXT record in Route 53, so no port is open for it. It renews twice a day when it expires within 30 days. Both renew on their own.
- **The instance** only accepts https from CloudFront, so the backend can't be reached around it. Port 80 and SSH are closed.
- **GitHub Actions** deploys a version tag, like `v2.0.0`, as an IAM role through OIDC. No access keys are stored in GitHub.
- **The backend** runs on a t4g.small (2 GB) with a fixed IP address. A deploy has a minute of downtime. For more room, set `InstanceType=t4g.medium` (4 GB) and raise `Xmx` in the template.
- **Platform updates** are installed on Sunday nights, and logs go to CloudWatch for 14 days.

One stack is one setup. `beta` runs next to the old production, on `beta-bnplanner.greaper.net` with the empty database `bnplanner2`.

## Costs

About $18 a month per setup: about $14 for the t4g.small and its disk, and about $3.65 for its IPv4 address. A t4g.medium makes it about $31. S3, CloudFront and Route 53 add very little. A 1-year Savings Plan on the instance saves about a third more.

While beta and the old production both run, you pay for both. The old one costs about $40 because of its load balancer.

## Setting up beta

### 1. Look up the certificate

The DNS of `greaper.net` is at Namecheap. Only the origin address gets its own Route 53 zone, created by the stack.

```powershell
aws acm list-certificates --region us-east-1 --query "CertificateSummaryList[?DomainName=='greaper.net' && Status=='ISSUED'].CertificateArn" --output text
```

This is the `CloudFrontCertificateArn`.

To check whether the account already knows GitHub (then use `CreateGitHubOidcProvider=false`):

```powershell
aws iam list-open-id-connect-providers
```

### 2. osu!

osu! allows one callback per OAuth application, so beta needs its own application. On https://osu.ppy.sh/home/account/edit, create a new OAuth application with the callback URL `https://beta-bnplanner.greaper.net/login/oauth2/code/osu`.

### 3. MongoDB Atlas

The backend creates the database `bnplanner2` on its own. The Atlas user needs `readWrite` on it. Network access must allow the instance. Its address changes when the instance is replaced, so in practice that means `0.0.0.0/0`, the same as the old production.

### 4. Create the stack

Run this in your own terminal from the root of the repository. It asks for the passwords, so they stay out of the template and out of your history.

```powershell
function Read-Secret($prompt) { [Net.NetworkCredential]::new("", (Read-Host $prompt -AsSecureString)).Password }
$mongoPassword = Read-Secret "MongoDB password"
$osuSecret = Read-Secret "osu! client secret"
aws cloudformation deploy --region eu-west-1 --stack-name bnplanner-beta --template-file infra/planner.yml --capabilities CAPABILITY_NAMED_IAM --parameter-overrides CloudFrontCertificateArn=... MongoHost=... MongoUsername=... "MongoPassword=$mongoPassword" OsuClientId=... "OsuClientSecret=$osuSecret"
```

This takes 15 to 20 minutes, mostly for CloudFront and Beanstalk. The backend starts with the sample application of Beanstalk until the first deploy.

The certificate is only requested once the first tag is deployed. If that deploy fails, the reason is in `/var/log/eb-hooks.log` of the instance: Beanstalk console, Logs, Request last 100 lines. Let's Encrypt gives out at most 5 of the same certificate a week, and a new instance asks again, so don't replace the instance over and over.

This needs an AWS user that can create IAM roles, CloudFront and Route 53 zones. The user `darius` can't, so sign in with `aws login --profile admin` and add `--profile admin`.

### 5. Namecheap

The outputs of the stack say where the records point to:

```powershell
aws cloudformation describe-stacks --region eu-west-1 --stack-name bnplanner-beta --query "Stacks[0].Outputs" --output table
```

Under Domain List, greaper.net, Advanced DNS, add:

| Type | Host | Value |
|---|---|---|
| CNAME | `beta-bnplanner` | `WebsiteCname`, like `d1234abcd.cloudfront.net` |
| NS | `beta-bnplanner-origin` | each of the 4 `OriginNameServers`, one record each |

These win over the wildcard `*.greaper.net`. Wait until `nslookup -type=NS beta-bnplanner-origin.greaper.net` shows the AWS name servers before the first deploy, as Let's Encrypt looks there.

Later changes to the template go through the same command. Parameters you leave out keep the value they had.

### 6. GitHub

In the repository settings, go to Environments and create `beta`. Add these variables (not secrets) from the outputs of the stack:

```powershell
aws cloudformation describe-stacks --region eu-west-1 --stack-name bnplanner-beta --query "Stacks[0].Outputs" --output table
```

| Variable | Output |
|---|---|
| `AWS_DEPLOY_ROLE` | `DeployRoleArn` |
| `WEBSITE_BUCKET` | `WebsiteBucket` |
| `CLOUDFRONT_DISTRIBUTION` | `DistributionId` |
| `BEANSTALK_ENVIRONMENT` | `BackendEnvironment` |

Optional: add yourself as a required reviewer, so every deploy waits for your approval.

### 7. Deploy

Push a version tag, like `git tag v2.0.0` and then `git push origin v2.0.0`. Both workflows check the tag and deploy it. Then open https://beta-bnplanner.greaper.net and log in.

## Taking the old data along

When beta works, copy the collections of the old database into `bnplanner2` with `mongodump` and `mongorestore`, or in Atlas: `beatmaps`, `users`, and whatever else the planner reads. Then restart the backend:

```powershell
aws elasticbeanstalk restart-app-server --region eu-west-1 --environment-name bnplanner-beta
```

Because `bnplanner2` has no finished migration yet, the backend reads the old `beatmaps` collection and copies it into `beatmaps2` in the background. Beatmaps that only exist on beta are removed along the way.

## Moving bnplanner.greaper.net over

1. Remove `bnplanner.greaper.net` from the aliases of the old distribution `E5XATFDRCHYNT`. Two distributions can't share an address.
2. Create a production stack, or update this one, with `Domain=bnplanner.greaper.net`, an `OriginDomain` such as `bnplanner-origin.greaper.net`, and the production osu! application with the callback `https://bnplanner.greaper.net/login/oauth2/code/osu`.
3. Point the GitHub environment variables at it.

## Cleaning up afterwards

These are leftovers in the account. Delete them only once the new setup runs, because none of them can be undone:

- the old Beanstalk environment `bnplanner-env`, which also removes its load balancer, and the certificate `greaper.net` in eu-west-1, which only that load balancer used
- the old distribution `E5XATFDRCHYNT` and the public bucket `bnplanner.greaper.net` in eu-central-1
- the disabled distributions `new-bnplanner.greaper.net` and `newbnplanner.greaper.net` from 2022, whose buckets no longer exist
- the expired certificate `www.bnplanner.xyz` in us-east-1
- the bucket `elasticbeanstalk-eu-central-1-…` with wars from 2020, and old application versions in `elasticbeanstalk-eu-west-1-…`
- the secrets `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` in GitHub, and the access key behind them in IAM

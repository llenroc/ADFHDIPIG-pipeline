
// includes
const config = require("config");
const crypto = require("crypto");
const adal = require("adal-node");
const azure = require("azure-storage");
const request = require("request");
const express = require("express");
const big = require("big-number");
const promisePool = require('es6-promise-pool');
const moment = require("moment");
const stream = require("stream");
const cookieParser = require("cookie-parser");
const qs = require("querystring");
const nJwt = require("njwt");

// global variables
const directory = config.get("internal.directory");
const subscriptionId = config.get("internal.subscriptionId");
const clientId = config.get("internal.clientId");
const clientSecret = config.get("internal.clientSecret");
const adf_version = config.get("internal.adf_version");

// instantiate express
const app = express();
app.use(cookieParser());
app.use(express.static("client"));

express.request.accessToken = function() {
    const req = this;
    if (req.cookies.accessToken) return req.cookies.accessToken;
    if (req.get("Authorization")) return req.get("Authorization").replace("Bearer ", "");
    return null;
};

express.request.hasRights = function(rights) {
    return new Promise((resolve, reject) => {
        const req = this;
        const token = req.accessToken();
        if (token) {
            nJwt.verify(token, jwtKey, function(err, verified) {
                if (!err) {
                    if (Array.isArray(rights)) {
                        if (verified.body.rights.hasIntersection(rights)) {
                            resolve(verified);
                        } else {
                            reject("authorization");
                        }
                    } else {
                        if (verified.body.rights.indexOf(rights) > -1) {
                            resolve(verified);
                        } else {
                            reject("authorization");
                        }
                    }
                } else {
                    reject("authentication", err);
                }
            });
        } else {
            reject("authentication");
        }
    });
};

express.request.hasRight = function(right) {
    return this.hasRights(right); // this is just an alias
};

// extend string to convert to array
String.prototype.toArrayOfStrings = function() {
    return this.split(",").map(function(code) {
        return code.trim();
    });
}

// break apart a WASB SAS URL into it's component parts
function WasbSasUrlToComponents(url) {
    const hostToken = url.split("?", 2);
    const hostContainer = hostToken[0].split("/");
    return {
        host: "https://" + hostContainer[2],
        container: hostContainer[3],
        token: hostToken[1]
    };
}

// redirect to default page
app.get("/", (req, res) => {
    res.redirect("/default.html");
});

// get a list of pipelines
app.get("/pipelines", (req, res) => {
    req.hasRight("read").then(token => {

        // authenticate against Azure APIs
        const context = new adal.AuthenticationContext("https://login.microsoftonline.com/" + directory);
        context.acquireTokenWithClientCredentials("https://management.core.windows.net/", clientId, clientSecret, function(err, tokenResponse) {
            if (!err) {

                // get the pipelines
                request.get({
                    uri: `https://management.azure.com/subscriptions/${subscriptionId}/resourcegroups/${token.resourceGroup}/providers/Microsoft.DataFactory/datafactories/${token.dataFactory}/datapipelines?api-version=${adf_version}`,
                    headers: { Authorization: "Bearer " + tokenResponse.accessToken },
                    json: true
                }, (err, response, body) => {
                    if (!err && response.statusCode == 200) {
                        res.send(response.body.value);
                    } else {
                        if (err) { console.error("err(201): " + err) } else { console.error("err(202) [" + response.statusCode + "]: " + response.statusMessage); console.log(body); };
                    }
                });

            } else {
                res.status(500).send("err(200): Server calls could not authenticate.");
            }
        });

    }, reason => {
        if (reason === "authentication") {
            res.redirect("/login");
        } else {
            res.status(401).send("unauthorized");
        }
    });
});

// get a list of slices
app.get("/slices", (req, res) => {
    let datasets = req.query.datasets;
    if (datasets) {
        datasets = datasets.toArrayOfStrings();

        // authenticate against Azure APIs
        const context = new adal.AuthenticationContext("https://login.microsoftonline.com/" + directory);
        context.acquireTokenWithClientCredentials("https://management.core.windows.net/", clientId, clientSecret, function(err, tokenResponse) {
            if (!err) {

                const resourceGroup = "pelasne-adf";
                const dataFactory = "pelasne-adf";

                const now = new Date("2017-05-14T00:00:00Z");
                const startTimestamp = new Date(now - 96 * 60 * 60 * 1000).toISOString(); // 96 hours back
                const endTimestamp = now.toISOString();

                const slices = [];

                // build a pool of queries to get data on all the specified datasets
                let index = 0;
                const pool = new promisePool(() => {
                    if (index < datasets.length) {
                        const dataset = datasets[index];
                        index++;
                        return new Promise((resolve, reject) => {
                            request.get({
                                uri: `https://management.azure.com/subscriptions/${subscriptionId}/resourcegroups/${resourceGroup}/providers/Microsoft.DataFactory/datafactories/${dataFactory}/datasets/${dataset}/slices?start=${startTimestamp}&end=${endTimestamp}&api-version=${adf_version}`,
                                headers: { Authorization: "Bearer " + tokenResponse.accessToken },
                                json: true
                            }, (err, response, body) => {
                                if (!err && response.statusCode == 200) {
                                    response.body.value.forEach(slice => {
                                        slice.dataset = dataset;
                                        slices.push(slice);
                                    });
                                    resolve();
                                } else {
                                    reject( err || new Error(response.body) );
                                }
                            });
                        });
                    } else {
                        return null;
                    }
                }, 4);
                
                // process the queries 4 at a time and when done send the results
                pool.start().then(() => {
                    res.send(slices);
                }, error => {
                    res.status(500).send("err(300): " + error.message);
                });

            } else {
                res.status(500).send("err(200): Server calls could not authenticate.");
            }
        });

    } else {
        res.status(400).send("err(300): You must supply a dataset parameter.");
    }
});

// get details on a specific slice run
app.get("/slice", (req, res) => {
    const dataset = req.query.dataset;
    const start = parseInt(req.query.start);
    if (dataset && !isNaN(start)) {
        const startTimestamp = new moment(start).utc().format("YYYY-MM-DDTHH:mm:ss") + ".0000000";

        // authenticate against Azure APIs
        const context = new adal.AuthenticationContext("https://login.microsoftonline.com/" + directory);
        context.acquireTokenWithClientCredentials("https://management.core.windows.net/", clientId, clientSecret, function(err, tokenResponse) {
            if (!err) {

                const resourceGroup = "pelasne-adf";
                const dataFactory = "pelasne-adf";

                request.get({
                    uri: `https://management.azure.com/subscriptions/${subscriptionId}/resourcegroups/${resourceGroup}/providers/Microsoft.DataFactory/datafactories/${dataFactory}/datasets/${dataset}/sliceruns?startTime=${startTimestamp}&api-version=${adf_version}`,
                    headers: { "Authorization": "Bearer " + tokenResponse.accessToken },
                    json: true
                }, (err, response, body) => {
                    if (!err && response.statusCode == 200) {
                        res.send(response.body.value);
                    } else {
                        res.status(500).send("err(400): " + (err || JSON.stringify(response.body)));
                    }
                });

            } else {
                res.status(500).send("err(200): Server calls could not authenticate.");
            }
        });

    } else {
        res.status(400).send("err(300): You must supply a dataset and start parameter.");
    }
});

// get a list of all log files that can be downloaded
app.get("/logs", (req, res) => {
    const runId = req.query.runId;
    const start = parseInt(req.query.start);
    if (runId && !isNaN(start)) {
        const startTimestamp = new moment(start).utc().format("YYYY-MM-DDTHH:mm:ss") + ".0000000";

        // authenticate against Azure APIs
        const context = new adal.AuthenticationContext("https://login.microsoftonline.com/" + directory);
        context.acquireTokenWithClientCredentials("https://management.core.windows.net/", clientId, clientSecret, function(err, tokenResponse) {
            if (!err) {

                const resourceGroup = "pelasne-adf";
                const dataFactory = "pelasne-adf";

                request.get({
                    uri: `https://management.azure.com/subscriptions/${subscriptionId}/resourcegroups/${resourceGroup}/providers/Microsoft.DataFactory/datafactories/${dataFactory}/runs/${runId}/logInfo?start${startTimestamp}&api-version=${adf_version}`,
                    headers: { "Authorization": "Bearer " + tokenResponse.accessToken },
                    json: true
                }, (err, response, body) => {
                    if (!err && response.statusCode == 200) {

                        const components = WasbSasUrlToComponents(response.body);
                        const blobsvc = azure.createBlobServiceWithSas(components.host, components.token);

                        blobsvc.listBlobsSegmented(components.container, null, {
                            maxResults: 100
                        }, function(err, result, response) {
                            if (!err && response.statusCode == 200) {

                                // return a list of log files
                                const files = [];
                                response.body.EnumerationResults.Blobs.Blob.forEach(blob => {
                                    if (parseInt(blob.Properties["Content-Length"]) > 0) {
                                        files.push({
                                            name: blob.Name,
                                            url: components.host + "/" + components.container + "/" + blob.Name + "?" + components.token
                                        });
                                    }
                                });
                                res.send(files);

                            } else {
                                res.status(500).send("err(500): " + response.statusCode + ": " + response.statusMessage);
                            }
                        });


                        //res.send(response.body);
                    } else {
                        res.status(500).send("err(400): " + (err || JSON.stringify(response.body)));
                    }
                });

            } else {
                res.status(500).send("err(200): Server calls could not authenticate.");
            }
        });

    } else {
        res.status(400).send("err(300): You must supply a runId and start parameter.");
    }
});

// get instance logs
//   NOTE: instanceId = <customerId>-<activityName>-<sliceStart>
//     ex:              HSKY-NormalizeUsingPig-20170324T1300
app.get("/instance", (req, res) => {
    const instanceId = req.query.instanceId;
    if (instanceId) {

        // instantiate the table service
        const account = config.get("storage.account");
        const key = config.get("storage.key");
        const service = azure.createTableService(account, key);

        // ensure the table exists
        const table = config.get("storage.table");
        createTableIfNotExists(service, table).then(result => {

            // read all instance logs
            queryForAllInstances(service, instanceId).then(entries => {
                const output = entries.map(entry => {
                    return {
                        index: entry.RowKey._,
                        ts: entry.Timestamp._,
                        level: entry.Level._,
                        msg: entry.Message._,
                        apk: entry.AssociatedPK._,
                        ark: entry.AssociatedRK._
                    };
                });

                const file = new stream.Readable();
                file.push("index,timestamp,level,message\n");
                output.forEach(line => {
                    file.push(`${line.index},${line.ts},${line.level},"${line.msg}"\n`);
                });
                file.push(null);
                res.writeHead(200, {
                    "Content-Type": "application/csv",
                    "Content-Disposition": "attachment; filename=\"" + instanceId + ".csv\""
                });
                file.pipe(res);

            }, error => {
                console.error(error);
                res.status(500).send({ message: "Failed to read logs. Please try again in a bit.", ex: error });
            });

        }, error => {
            console.error(error);
        });

    } else {
        res.send([]); // empty array of objects
    }
});

app.get("/logout", function(req, res) {
    res.cookie("accessToken", "", { expires: new Date() });
    res.redirect("/default.html");
});

// redirect through the AAD consent pattern
function consent(res, add) {
    const clientId = config.get("external.clientId");
    const redirectUri = config.get("external.redirectUri");
    crypto.randomBytes(48, function(err, buf) {
        if (err) {
            res.status(500).send("Server Error: a crypto token couldn't be created to secure the session.");
        } else {
            const token = buf.toString("base64").replace(/\//g, "_").replace(/\+/g, "-");
            res.cookie("authstate", token, { maxAge: 10 * 60 * 1000 }); // 10 min
            const url = "https://login.microsoftonline.com/common/oauth2/authorize?response_type=code&client_id=" + qs.escape(clientId) + "&redirect_uri=" + qs.escape(redirectUri) + "&state=" + qs.escape(token) + "&resource=" + qs.escape("https://graph.microsoft.com/") + add;
            res.redirect(url);
        }
    });
}

// a login with administrative consent
app.get("/consent", function(req, res) {
    consent(res, "&prompt=admin_consent");
});

// a login with user consent (if the admin has already consented there is no additional consent required)
app.get("/login", function(req, res) {
    if (req.query.redirect) {
        res.cookie("redirect", req.query.redirect, { maxAge: 10 * 60 * 1000 }); // 10 min
    }
    consent(res, "");
});

// once a user has authenticated, generate their authorization token
app.get("/token", function(req, res) {
    
    // get the variables for external application
    const clientId = config.get("external.clientId");
    const clientSecret = config.get("external.clientSecret");
    const redirectUri = config.get("external.redirectUri");
    const jwtKey = config.get("external.jwtKey");
    const issuer = config.get("external.issuer");
    const groupPrefix = config.get("external.groupPrefix");

    // ensure this is all part of the same authorization chain
    if (req.cookies.authstate !== req.query.state) {
        res.status(400).send("Bad Request: this does not appear to be part of the same authorization chain.");
    } else {
    
        // get authorization for the Microsoft Graph
        const context = new adal.AuthenticationContext("https://login.microsoftonline.com/common");
        context.acquireTokenWithAuthorizationCode(req.query.code, redirectUri, "https://graph.microsoft.com/", clientId, clientSecret, function(tokenError, tokenResponse) {
            if (!tokenError) {

                // get the user membership
                request.get({
                    uri: "https://graph.microsoft.com/v1.0/me/memberOf?$select=displayName",
                    headers: {
                        Authorization: "Bearer " + tokenResponse.accessToken
                    },
                    json: true
                }, function(membershipError, response, body) {
                    if (!membershipError && response.statusCode == 200) {

                        // build a list of group names
                        const membership = [];
                        body.value.forEach(group => {
                            if (group.displayName.startsWith(groupPrefix)) {
                                membership.push(group.displayName.replace(groupPrefix, ""));
                            }
                        });

                        // define rights
                        const rights = [];
                        if (membership.indexOf("admins") > -1) {
                            rights.push("admin");
                            rights.push("write");
                            rights.push("read");
                        } else if (membership.indexOf("users") > -1) {
                            rights.push("read");
                        }

                        // build the claims (no sensitive information)
                        const claims = {
                            iss: issuer,
                            sub: tokenResponse.userId,
                            scope: membership,
                            rights: rights,
                            resourceGroup: "pelasne-adf",
                            dataFactory: "pelasne-adf"
                        };

                        // build the JWT
                        const duration = 4 * 60 * 60 * 1000; // 4 hours
                        const jwt = nJwt.create(claims, jwtKey);
                        jwt.setExpiration(new Date().getTime() + duration);

                        // set the JWT into a cookie
                        res.cookie("accessToken", jwt.compact(), {
                            maxAge: duration
                        });

                        // redirect
                        if (req.cookies.redirect) {
                            res.cookie("redirect", "", { expires: new Date() });
                            res.redirect(req.cookies.redirect);
                        } else {
                            res.redirect("/pipelines.html");
                        }

                    } else {
                        res.status(401).send("Unauthorized (membership): " + ((membershipError) ? membershipError : response.statusCode + ", " + body));
                    }
                });

            } else {
                res.status(401).send("Unauthorized (access code): " + tokenError);
            }
        });

    }
 
});

// startup the server
app.listen(80, () => {

/*
    const long = big(2).power(63).subtract(1);
    const current = Date.parse("2017-06-19T00:00:00.000Z");
    const s = long.subtract(current).toString();
    console.log(s);
*/

    console.log("listening on port 80...");
});
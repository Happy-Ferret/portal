var portal = portal || {};
portal.Location = portal.Location || {};
portal.User = portal.User || {};
portal.Socket = portal.Socket || {};
(function(exports) {

    var waitingQueue ={};
    var wsPromise = Q.defer();
    var correlationIdCounter = 0;
    var defaultOptions = {
        topic : '/portal/topics/default',
        timeout: 60000,
        payload: {}
    };

    var socket;
    var lastToken;

    function init() {
        socket = new WebSocket("ws://" + location.host + "/ws");
        socket.onopen = function() {
            socket.send(JSON.stringify({
                topic: "/portal/topics/default",
                command: "first",
                url: window.location.pathname
            }));
            console.log("Websocket connection successful");
        };
        socket.onerror = function() {
            wsPromise.reject(new Error("The websocket cannot be initialized"));
            console.error("The websocket cannot be initialized");
        };
        socket.onmessage = function(event) {
            //console.trace('data received on user websocket : ' + event.data);
            // TODO : handle special messages like : redirect to internal page, add mashete, etc ...
            var data = JSON.parse(event.data);
            if (data.firstConnection) {
                portal.Location.current = portal.Location.current || data.page;
                portal.User.current = portal.User.current || data.user;
                portal.User.current.isAdmin = function() {
                    return ! _.isUndefined(_.find(portal.User.current.roles, function(role) { return role.name === "ADMINISTRATOR"; }));
                };
                portal.User.current.isNotAdmin = function() {
                    return !portal.User.current.isAdmin();
                };
                lastToken = data.token;
                console.log("Successful first exchange");
                wsPromise.resolve({});
            } else {
                lastToken = data.token;
                var correlationId = data.correlationId;
                if (!data.token) {
                    console.error('No token, WTF ???');
                }
                if (waitingQueue[correlationId]) {
                    var promise = waitingQueue[correlationId];
                    promise.resolve(data.response);
                    delete waitingQueue[correlationId];
                } else {
                    console.error("Correlation " + correlationId + " not in waiting queue");
                }
            }
        };
        return wsPromise.promise;
    }

    function ask(options) {
        var promise = Q.defer();
        var future = promise.promise;
        var opt = _.extend({}, defaultOptions, options);
        var correlationId = 'promise-' + (correlationIdCounter++);
        opt.correlationId = correlationId;
        opt.token = lastToken;
        waitingQueue[correlationId] = promise;
        var pl = JSON.stringify(opt);
        //console.trace('Data asked on user websocket ' + pl);
        socket.send(pl);
        setTimeout(function() {
            if (waitingQueue[correlationId]) {
                delete waitingQueue[correlationId];
                promise.reject(new Error("Request timeout"));
            }
        }, opt.timeout);
        return future;
    }

    function tell(options) {
        var opt = _.extend({}, defaultOptions, options);
        opt.correlationId = 'promise-' + (correlationIdCounter++);
        opt.token = lastToken;
        var pl = JSON.stringify(opt);
        //console.trace('Data sent on user websocket ' + pl);
        socket.send(pl);
    }

    exports.ask = ask;

    exports.tell = tell;

    exports.init = init;

})(portal.Socket);
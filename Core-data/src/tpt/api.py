#!/usr/bin/env python

import contextlib
import logging
import urllib2
from flask import Flask, request, jsonify
from werkzeug.contrib.fixers import ProxyFix
app = Flask(__name__)

import tpt.db
import tpt.tools


def build_response(status="success", message=None, code=200, extras={}):
    resp = {"status": status}
    if message is not None:
        resp["message"] = message
    resp.update(extras)
    resp = jsonify(resp)
    resp.status_code = code
    return resp


def not_found_response(extras={}):
    return build_response("error", "Not Found: " + request.url, 404, extras)


def exception_response(extras={}):
    return build_response("error", "Internal server error", 500, extras)


@contextlib.contextmanager
def create_db_cursor():
    try:
        conn = tpt.db.open_connection()
        cursor = conn.cursor()
        try:
            yield cursor
            conn.commit()
        except:
            conn.rollback()
            raise
        finally:
            cursor.close()
            conn.close()
    except Exception:
        app.logger.exception("Error at {0}".format(request.url))


@contextlib.contextmanager
def not_found_handler():
    try:
        yield None
    except tpt.db.ItemNotFoundException, e:
        app.logger.info(e.message)


@app.errorhandler(404)
def not_found(error=None):
    err = {"error_code": error.code} if error is not None else {}
    return not_found_response(err)


@app.route('/', methods=["GET", "POST"])
def do_api_root():
    return build_response(message="Welcome\n")


@app.route('/generate_device_id', methods=["GET", "POST"])
def do_generate_device_id():
    with create_db_cursor() as cursor:
        device_id = tpt.tools.use_device_id(cursor)
        return device_id
    return 'NONE\n'


@app.route('/post_times_bundle', methods=["POST", "GET"])
def do_post_times_bundle():
    with create_db_cursor() as cursor:
        with not_found_handler():
            tpt.tools.insert_times_log(cursor, request.stream)
            return 'Thank you\n'
        return not_found()
    return 'Not saved, thank you anyway\n'


def _get_fields():
    fields = []
    if "fields" in request.args:
        fields = request.args.get('fields', "").split(",")
    else:
        fields = request.args.keys()
    return tuple(f.strip() for f in fields if f.strip())


def _map_routes(cursor, fields, routes):
    for route in routes:
        item_dict = {}
        line = tpt.db.find_line(cursor, route[1])
        item_dict.update(line[3])
        item_dict.update({
            "title": line[1],
            "vehicle_type": line[2]
        })

        item_dict.update(route[3])
        item_dict.update(route[4])

        stations = tpt.db.find_route_stations(cursor, route[0], (
            "route_stop_id", "stop_index", "stop_id"))
        # TODO find better way for getting a departure name
        item_dict.update({
            "route_id": route[0],
            "destination": route[2],
            "departure": stations[0][2]
        })
        yield dict((k, v) for k, v in item_dict.iteritems()
                   if k in fields)


def _map_route_stops(cursor, fields, stops):
    import ipdb; ipdb.set_trace()
    for stop in stops:
        item_dict = {}
        eattrs = stop[5]
        attrs = stop[4]
        item_dict.update(eattrs)
        item_dict.update(attrs)
        item_dict.update({
            "stop_id": stop[0],
            "stop_index": stop[1],
            "title": stop[2],
            "gps_pos": stop[3],
            "is_enabled": stop[6],
            "next_eta": "--"
        })
        yield dict((k, v) for k, v in item_dict.iteritems()
                   if k in fields)


def _do_any_routes(route_gen):
    with create_db_cursor() as cursor:
        with not_found_handler():
            # TODO: paginate routes
            routes = list(_map_routes(
                cursor, _get_fields(), route_gen(cursor)))
            return build_response(extras={"routes": routes})
        return not_found_response({"routes": []})
    return exception_response({"routes": []})


def _mock_route_stops(fields):
    s1 = {"id": 123, "extid": "1234", "title": "Catedrala Mitropolitana",
          "next_eta": "1min"}
    s2 = {"id": 123, "extid": "1234", "title": "Pod C. Sagului",
          "next_eta": "2min"}
    result = {"status": "success", "stops": [s1, s2]}
    return jsonify(result)


@app.route('/v1/routes', methods=["GET"])
def do_routes():
    def routes_gen(cursor):
        lines = tpt.db.find_all_active_lines(cursor)
        for l in lines:
            pass
    return _do_any_routes(routes_gen)


@app.route('/v1/routes/<route_id>', methods=["GET"])
def do_routes_with_id(route_id):
    def routes_gen(cursor):
        return [tpt.db.find_route(cursor, route_id)]
    return _do_any_routes(routes_gen)


@app.route('/v1/cities/<city_id>/tram/routes', methods=["GET"])
def do_tram_routes(city_id):
    def routes_gen(cursor):
        return tpt.db.find_active_routes_by_type(cursor, city_id, 0)
    return _do_any_routes(routes_gen)


@app.route('/v1/cities/<city_id>/trolleybus/routes', methods=["GET"])
def do_trollebus_routes(city_id):
    def routes_gen(cursor):
        return tpt.db.find_active_routes_by_type(cursor, city_id, 1)
    return _do_any_routes(routes_gen)


@app.route('/v1/cities/<city_id>/any_bus/routes', methods=["GET"])
def do_any_bus_routes(city_id):
    def routes_gen(cursor):
        buses = tpt.db.find_active_routes_by_type(cursor, city_id, 2)
        express = tpt.db.find_active_routes_by_type(cursor, city_id, 3)
        metro = tpt.db.find_active_routes_by_type(cursor, city_id, 4)
        return buses + express + metro
    return _do_any_routes(routes_gen)


@app.route('/v1/cities/<city_id>/favorite/routes', methods=["GET"])
def do_favorite_routes(city_id):
    def routes_gen(cursor):
        return tpt.db.find_favorite_routes(cursor)
    return _do_any_routes(routes_gen)


@app.route('/v1/routes/<route_id>/stops', methods=["GET"])
def do_route_stops(route_id):
    with create_db_cursor() as cursor:
        with not_found_handler():
            # TODO: paginate stops
            stops = list(_map_route_stops(
                cursor, _get_fields(),
                tpt.db.find_route_stations(cursor, route_id, _get_fields())))
            return build_response(extras={"stops": stops})
        return not_found_response({"stops": []})
    return exception_response({"stops": []})


@app.route('/v1/routes/<route_id>/eta', methods=["GET"])
def get_route_eta(route_id):
    return build_response("error", "Not implemented", 500)


@app.route('/v1/eta/<route_ext_id>/<stop_ext_id>', methods=["GET"])
def get_eta(route_ext_id, stop_ext_id):
    url = "http://www.ratt.ro/txt/afis_msg.php?id_traseu={0}&id_statie={1}"
    resp = urllib2.urlopen(url.format(route_ext_id, stop_ext_id))
    content = resp.read()
    s1 = content.split("Sosire1:")[1].split("<br> Sosire2:")[0]
    return build_response(extras={"eta": s1})


app.wsgi_app = ProxyFix(app.wsgi_app)
app.debug = False

if not app.debug:
    from logging.handlers import SysLogHandler
    handler = SysLogHandler(address='/dev/log')
    handler.setLevel(logging.INFO)
    app.logger.addHandler(handler)

if __name__ == '__main__':
    app.run(host="0.0.0.0", port=8080)
